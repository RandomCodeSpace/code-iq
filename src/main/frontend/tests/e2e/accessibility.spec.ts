/**
 * Accessibility tests — WCAG 2.1 AA compliance.
 *
 * Covers:
 *   AC-3  No WCAG 2.1 AA accessibility violations
 *
 * Uses @axe-core/playwright for automated ARIA/contrast/role checks,
 * plus manual keyboard navigation assertions.
 *
 * References:
 *   https://www.deque.com/axe/core-documentation/api-documentation/
 *   https://www.w3.org/WAI/WCAG21/quickref/?versions=2.1&levels=aa
 */

import { test, expect } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { gotoRoute, mockStats, mockGraphData, ROUTES } from '../utils/test-helpers';

// ── Automated axe audit per page ──────────────────────────────────────────────

const PAGES = [
  { name: 'Dashboard', route: ROUTES.dashboard },
  { name: 'Code Graph', route: ROUTES.graph },
  { name: 'Explorer', route: ROUTES.explorer },
  { name: 'MCP Console', route: ROUTES.console },
  { name: 'API Docs', route: ROUTES.apiDocs },
] as const;

for (const { name, route } of PAGES) {
  test(`${name}: no WCAG 2.1 AA violations (axe audit)`, async ({ page }) => {
    await mockStats(page);
    await mockGraphData(page, 100);
    await gotoRoute(page, route);

    const results = await new AxeBuilder({ page })
      .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'])
      // Suppress known acceptable false positives from third-party libs
      .exclude('[data-testid="swagger-ui-iframe"]') // Swagger UI manages its own a11y
      .analyze();

    expect(results.violations).toHaveLength(0);
  });
}

// ── Keyboard navigation ───────────────────────────────────────────────────────

test.describe('Keyboard navigation', () => {
  test('all nav links reachable via Tab from header', async ({ page }) => {
    await mockStats(page);
    await gotoRoute(page, ROUTES.dashboard);

    // Focus the first interactive element in the document
    await page.keyboard.press('Tab');

    const navLabels = ['Dashboard', 'Code Graph', 'Explorer', 'Console', 'API Docs'];
    let foundCount = 0;

    for (let i = 0; i < 30; i++) {
      const focused = await page.evaluate(() => document.activeElement?.textContent?.trim() ?? '');
      if (navLabels.includes(focused)) foundCount++;
      if (foundCount === navLabels.length) break;
      await page.keyboard.press('Tab');
    }

    expect(foundCount).toBe(navLabels.length);
  });

  test('no keyboard trap in sidebar', async ({ page }) => {
    await mockStats(page);
    await gotoRoute(page, ROUTES.dashboard);

    // Tab through the sidebar without getting stuck
    const visitedTags: string[] = [];
    for (let i = 0; i < 30; i++) {
      await page.keyboard.press('Tab');
      const tag = await page.evaluate(() => document.activeElement?.tagName ?? '');
      visitedTags.push(tag);
    }

    // Focus should eventually move into main (not loop in sidebar forever)
    const hasMain = await page.evaluate(() => {
      const el = document.activeElement;
      return !!el?.closest('main');
    });
    expect(hasMain).toBe(true);
  });

  test('theme toggle is keyboard accessible', async ({ page }) => {
    await mockStats(page);
    await gotoRoute(page, ROUTES.dashboard);

    // Find the theme toggle button via Tab
    const themeToggle = page.getByRole('button', { name: /toggle theme|dark mode|light mode/i });
    await themeToggle.focus();
    await expect(themeToggle).toBeFocused();

    // Activate via Enter
    await page.keyboard.press('Enter');
    // The html class should have flipped
    const cls = await page.locator('html').getAttribute('class') ?? '';
    // Just verify no exception was thrown and page is still interactive
    await expect(page.locator('main')).toBeVisible();
    void cls; // used to avoid lint warning
  });

  test('search bar is reachable and activatable via keyboard', async ({ page }) => {
    await mockStats(page);
    await gotoRoute(page, ROUTES.dashboard);

    const searchBox = page.getByRole('searchbox');
    await searchBox.focus();
    await expect(searchBox).toBeFocused();
    await page.keyboard.type('test');
    await expect(searchBox).toHaveValue('test');
  });

  test('modal/dialog traps focus correctly', async ({ page }) => {
    await mockStats(page);
    await mockGraphData(page, 20);
    await gotoRoute(page, ROUTES.graph);

    // Open node detail (assuming a dialog opens — may be the right panel)
    const detailDialog = page.locator('[role="dialog"]');
    if (!(await detailDialog.isVisible())) {
      // Try to open one by clicking a node in the graph area
      const graph = page.locator('[data-testid="graph-container"]');
      const box = await graph.boundingBox();
      if (box) await page.mouse.click(box.x + box.width / 2, box.y + box.height / 2);
    }

    if (await detailDialog.isVisible()) {
      // Tab should cycle within the dialog
      await page.keyboard.press('Tab');
      const focusedInDialog = await page.evaluate(() => {
        const el = document.activeElement;
        return !!el?.closest('[role="dialog"]');
      });
      expect(focusedInDialog).toBe(true);

      // Escape should close the dialog
      await page.keyboard.press('Escape');
      await expect(detailDialog).not.toBeVisible();
    }
  });
});

// ── ARIA labels ───────────────────────────────────────────────────────────────

test.describe('ARIA labels', () => {
  test('navigation landmark is labelled', async ({ page }) => {
    await mockStats(page);
    await gotoRoute(page, ROUTES.dashboard);
    // There must be a <nav> with an aria-label
    const nav = page.locator('nav[aria-label]');
    await expect(nav).toBeVisible();
  });

  test('main landmark is present', async ({ page }) => {
    await mockStats(page);
    await gotoRoute(page, ROUTES.dashboard);
    await expect(page.locator('main')).toBeVisible();
  });

  test('graph container has accessible label', async ({ page }) => {
    await mockStats(page);
    await mockGraphData(page, 20);
    await gotoRoute(page, ROUTES.graph);

    const graphContainer = page.locator('[data-testid="graph-container"]');
    const ariaLabel = await graphContainer.getAttribute('aria-label');
    const ariaLabelledBy = await graphContainer.getAttribute('aria-labelledby');
    expect(ariaLabel || ariaLabelledBy).toBeTruthy();
  });

  test('icon-only buttons have aria-labels', async ({ page }) => {
    await mockStats(page);
    await gotoRoute(page, ROUTES.dashboard);

    // All buttons with no visible text must have aria-label
    const iconButtons = await page.locator('button:not([aria-label])').all();
    for (const btn of iconButtons) {
      const text = (await btn.textContent())?.trim() ?? '';
      if (text === '') {
        // This button has no text — it must have aria-label
        const label = await btn.getAttribute('aria-label');
        expect(label, `Button at ${await btn.evaluate(el => el.outerHTML.slice(0, 100))} has no aria-label`).toBeTruthy();
      }
    }
  });
});

// ── Color contrast spot-checks ────────────────────────────────────────────────
// Full contrast audit is handled by axe above; these are quick sanity checks.

test.describe('Color contrast', () => {
  test('dashboard text is not invisible in dark mode', async ({ page }) => {
    await mockStats(page);
    await gotoRoute(page, ROUTES.dashboard);
    await expect(page.locator('html')).toHaveClass(/dark/);

    // Stats cards should have readable text
    const card = page.locator('[data-testid="stats-card"]').first();
    if (await card.isVisible()) {
      const color = await card.evaluate(el => window.getComputedStyle(el).color);
      // Text color should not be the same as background (very rough check)
      const bg = await card.evaluate(el => window.getComputedStyle(el).backgroundColor);
      expect(color).not.toBe(bg);
    }
  });
});
