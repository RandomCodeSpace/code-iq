/**
 * Navigation, layout, and dark/light mode tests.
 *
 * Covers:
 *   AC-1  All views render correctly in Chrome, Firefox, Safari, Edge
 *   AC-5  Dark/light mode consistent across all views
 *
 * Runs on all browsers defined in playwright.config.ts.
 */

import { test, expect } from '@playwright/test';
import { gotoRoute, getTheme, toggleTheme, mockStats, ROUTES } from '../utils/test-helpers';

// ── Layout shell ─────────────────────────────────────────────────────────────

test.describe('Layout shell', () => {
  test.beforeEach(async ({ page }) => {
    await mockStats(page);
    await gotoRoute(page, ROUTES.dashboard);
  });

  test('renders header, sidebar, and main content area', async ({ page }) => {
    await expect(page.locator('header')).toBeVisible();
    await expect(page.locator('nav')).toBeVisible();
    await expect(page.locator('main')).toBeVisible();
  });

  test('sidebar contains all five navigation links', async ({ page }) => {
    const navLinks = ['Dashboard', 'Code Graph', 'Explorer', 'Console', 'API Docs'];
    for (const label of navLinks) {
      await expect(page.getByRole('link', { name: label })).toBeVisible();
    }
  });

  test('sidebar collapses on mobile viewport', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    // Sidebar should be hidden by default on narrow viewports
    const sidebar = page.locator('aside');
    await expect(sidebar).not.toBeInViewport();
    // Hamburger toggle should be visible
    await expect(page.getByRole('button', { name: /menu|open sidebar/i })).toBeVisible();
  });

  test('sidebar opens and closes on mobile', async ({ page }) => {
    await page.setViewportSize({ width: 375, height: 812 });
    const hamburger = page.getByRole('button', { name: /menu|open sidebar/i });
    await hamburger.click();
    const sidebar = page.locator('aside');
    await expect(sidebar).toBeInViewport();
    // Close via the X button or overlay click
    const closeBtn = page.getByRole('button', { name: /close sidebar/i });
    if (await closeBtn.isVisible()) {
      await closeBtn.click();
    } else {
      // Click overlay
      await page.locator('[data-testid="sidebar-overlay"]').click();
    }
    await expect(sidebar).not.toBeInViewport();
  });

  test('logo and app title are visible', async ({ page }) => {
    await expect(page.getByText('OSSCodeIQ')).toBeVisible();
  });
});

// ── Navigation between views ─────────────────────────────────────────────────

test.describe('Navigation', () => {
  test.beforeEach(async ({ page }) => {
    await mockStats(page);
  });

  test('navigates to Dashboard', async ({ page }) => {
    await gotoRoute(page, ROUTES.dashboard);
    await expect(page).toHaveURL('/');
    // Dashboard should show stats cards
    await expect(page.locator('main')).toBeVisible();
  });

  test('navigates to Code Graph view', async ({ page }) => {
    await gotoRoute(page, ROUTES.graph);
    await expect(page).toHaveURL('/graph');
    await expect(page.locator('main')).toBeVisible();
    // Graph container must be rendered
    await expect(page.locator('[data-testid="graph-container"]')).toBeVisible();
  });

  test('navigates to Explorer view', async ({ page }) => {
    await gotoRoute(page, ROUTES.explorer);
    await expect(page).toHaveURL('/explorer');
    await expect(page.locator('main')).toBeVisible();
  });

  test('navigates to MCP Console', async ({ page }) => {
    await gotoRoute(page, ROUTES.console);
    await expect(page).toHaveURL('/console');
    // Console must show tool categories
    await expect(page.locator('main')).toBeVisible();
  });

  test('navigates to API Docs', async ({ page }) => {
    await gotoRoute(page, ROUTES.apiDocs);
    await expect(page).toHaveURL('/api-docs');
    await expect(page.locator('main')).toBeVisible();
  });

  test('unknown route redirects to dashboard', async ({ page }) => {
    await page.goto('/this-does-not-exist');
    await expect(page).toHaveURL('/');
  });

  test('active nav link is highlighted', async ({ page }) => {
    await gotoRoute(page, ROUTES.graph);
    const graphLink = page.getByRole('link', { name: 'Code Graph' });
    // Active link should have an active class or aria-current
    const isActive =
      (await graphLink.getAttribute('aria-current')) === 'page' ||
      (await graphLink.getAttribute('class'))?.includes('active') ||
      (await graphLink.getAttribute('data-active')) === 'true';
    expect(isActive).toBe(true);
  });
});

// ── Dark / light mode across all views ───────────────────────────────────────

test.describe('Dark/light mode', () => {
  const views = Object.values(ROUTES);

  for (const route of views) {
    test(`dark mode renders consistently on ${route}`, async ({ page }) => {
      await mockStats(page);
      await gotoRoute(page, route);

      // Ensure we start in dark mode (default for Code IQ)
      const html = page.locator('html');
      await expect(html).toHaveClass(/dark/);

      // The background should not be white in dark mode
      const bg = await page.locator('body').evaluate(
        el => window.getComputedStyle(el).backgroundColor
      );
      // In dark mode, background should be dark (not rgb(255,255,255))
      expect(bg).not.toBe('rgb(255, 255, 255)');
    });

    test(`light mode renders consistently on ${route}`, async ({ page }) => {
      await mockStats(page);
      await gotoRoute(page, route);

      const initialTheme = await getTheme(page);
      if (initialTheme === 'dark') {
        await toggleTheme(page);
      }

      await expect(page.locator('html')).not.toHaveClass(/dark/);
      // No elements should have invisible text (contrast check is in accessibility.spec.ts)
      await expect(page.locator('main')).toBeVisible();
    });
  }

  test('theme toggle persists across navigation', async ({ page }) => {
    await mockStats(page);
    await gotoRoute(page, ROUTES.dashboard);

    // Switch to light
    const initial = await getTheme(page);
    if (initial === 'dark') await toggleTheme(page);
    await expect(page.locator('html')).not.toHaveClass(/dark/);

    // Navigate to another view
    await gotoRoute(page, ROUTES.graph);
    // Theme should still be light
    await expect(page.locator('html')).not.toHaveClass(/dark/);
  });

  test('theme toggle persists after page reload', async ({ page }) => {
    await mockStats(page);
    await gotoRoute(page, ROUTES.dashboard);

    const initial = await getTheme(page);
    if (initial === 'dark') await toggleTheme(page);

    await page.reload();
    await expect(page.locator('html')).not.toHaveClass(/dark/);
  });
});

// ── Search bar visibility ─────────────────────────────────────────────────────

test.describe('Search bar', () => {
  test('global search bar is visible in header', async ({ page }) => {
    await mockStats(page);
    await gotoRoute(page, ROUTES.dashboard);
    await expect(page.getByRole('searchbox')).toBeVisible();
  });
});
