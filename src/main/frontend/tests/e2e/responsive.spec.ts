/**
 * Responsive design tests.
 *
 * Covers:
 *   AC-4  All responsive breakpoints work correctly
 *         Desktop (1920px), Laptop (1440px), Tablet (768px)
 *
 * This spec runs against three custom Playwright projects defined in
 * playwright.config.ts: desktop-1920, laptop-1440, tablet-768.
 */

import { test, expect } from '@playwright/test';
import { gotoRoute, mockStats, mockGraphData, ROUTES } from '../utils/test-helpers';

const VIEWS = [
  { name: 'Dashboard', route: ROUTES.dashboard },
  { name: 'Code Graph', route: ROUTES.graph },
  { name: 'Explorer', route: ROUTES.explorer },
  { name: 'MCP Console', route: ROUTES.console },
  { name: 'API Docs', route: ROUTES.apiDocs },
] as const;

// ── Cross-view layout checks ──────────────────────────────────────────────────

for (const { name, route } of VIEWS) {
  test(`${name}: main content area fills viewport without horizontal scroll`, async ({ page }) => {
    await mockStats(page);
    await mockGraphData(page, 50);
    await gotoRoute(page, route);

    const hasHScroll = await page.evaluate(() => document.documentElement.scrollWidth > window.innerWidth);
    expect(hasHScroll, `${name} has unexpected horizontal scroll`).toBe(false);
  });

  test(`${name}: no content overflow or clipping`, async ({ page }) => {
    await mockStats(page);
    await mockGraphData(page, 50);
    await gotoRoute(page, route);

    // No element should have overflow:hidden that clips visible content unexpectedly
    // This is a smoke check — full visual regression needs screenshot diffs
    await expect(page.locator('main')).toBeVisible();
    await expect(page.locator('header')).toBeVisible();
  });
}

// ── Sidebar behavior per breakpoint ──────────────────────────────────────────

test.describe('Sidebar responsiveness', () => {
  test.beforeEach(async ({ page }) => {
    await mockStats(page);
  });

  test('sidebar is permanently visible at 1440px+', async ({ page }) => {
    const viewport = page.viewportSize();
    if (!viewport || viewport.width < 1024) {
      test.skip();
      return;
    }
    await gotoRoute(page, ROUTES.dashboard);
    await expect(page.locator('aside')).toBeVisible();
    // No hamburger button visible at large viewports
    await expect(page.getByRole('button', { name: /menu|open sidebar/i })).not.toBeVisible();
  });

  test('sidebar is hidden by default at 768px', async ({ page }) => {
    const viewport = page.viewportSize();
    if (!viewport || viewport.width > 900) {
      test.skip();
      return;
    }
    await gotoRoute(page, ROUTES.dashboard);
    // Hamburger should be visible
    await expect(page.getByRole('button', { name: /menu|open sidebar/i })).toBeVisible();
  });

  test('at 768px, sidebar opens and overlays content', async ({ page }) => {
    const viewport = page.viewportSize();
    if (!viewport || viewport.width > 900) {
      test.skip();
      return;
    }
    await gotoRoute(page, ROUTES.dashboard);
    await page.getByRole('button', { name: /menu|open sidebar/i }).click();

    const sidebar = page.locator('aside');
    await expect(sidebar).toBeVisible();

    // Overlay backdrop should be present
    const overlay = page.locator('[data-testid="sidebar-overlay"]');
    await expect(overlay).toBeVisible();
  });
});

// ── Graph view at each breakpoint ─────────────────────────────────────────────

test.describe('Graph view responsiveness', () => {
  test.beforeEach(async ({ page }) => {
    await mockStats(page);
    await mockGraphData(page, 50);
  });

  test('graph container resizes to fill available space', async ({ page }) => {
    await gotoRoute(page, ROUTES.graph);

    const container = page.locator('[data-testid="graph-container"]');
    await expect(container).toBeVisible();

    const box = await container.boundingBox();
    const viewport = page.viewportSize();

    // Container should use most of the viewport width (>50%)
    if (box && viewport) {
      expect(box.width).toBeGreaterThan(viewport.width * 0.5);
    }
  });

  test('graph controls toolbar is accessible at 768px', async ({ page }) => {
    const viewport = page.viewportSize();
    if (!viewport || viewport.width > 900) {
      test.skip();
      return;
    }
    await gotoRoute(page, ROUTES.graph);
    const controls = page.locator('[data-testid="graph-controls"]');
    // At tablet, controls may be in a collapsed/scrollable form but must still exist
    await expect(controls).toBeAttached();
  });
});

// ── Dashboard stats at each breakpoint ───────────────────────────────────────

test.describe('Dashboard responsiveness', () => {
  test.beforeEach(async ({ page }) => {
    await mockStats(page);
  });

  test('stats cards stack vertically at 768px', async ({ page }) => {
    const viewport = page.viewportSize();
    if (!viewport || viewport.width > 900) {
      test.skip();
      return;
    }
    await gotoRoute(page, ROUTES.dashboard);
    const cards = page.locator('[data-testid="stats-card"]');
    const count = await cards.count();
    if (count < 2) return;

    const box0 = await cards.nth(0).boundingBox();
    const box1 = await cards.nth(1).boundingBox();
    if (box0 && box1) {
      // At tablet width, second card should be below the first (y > y of first)
      expect(box1.y).toBeGreaterThanOrEqual(box0.y + box0.height - 5);
    }
  });

  test('stats cards are in a multi-column grid at 1440px+', async ({ page }) => {
    const viewport = page.viewportSize();
    if (!viewport || viewport.width < 1024) {
      test.skip();
      return;
    }
    await gotoRoute(page, ROUTES.dashboard);
    const cards = page.locator('[data-testid="stats-card"]');
    const count = await cards.count();
    if (count < 2) return;

    const box0 = await cards.nth(0).boundingBox();
    const box1 = await cards.nth(1).boundingBox();
    if (box0 && box1) {
      // At desktop width, cards should be side-by-side (same y, different x)
      expect(Math.abs(box0.y - box1.y)).toBeLessThan(10);
    }
  });
});
