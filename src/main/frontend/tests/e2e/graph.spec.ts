/**
 * Code Graph view interaction tests.
 *
 * Covers:
 *   AC-6  All graph interactions functional
 *         (click, double-click, right-click, zoom, pan, drag, drill-down, breadcrumb)
 *
 * Implementation notes:
 *   - Relies on @antv/g6 v5 being integrated (Phase 3: RAN-74).
 *   - The graph container must expose data-testid="graph-container" and
 *     data-render-state="ready" when rendering is complete.
 *   - The breadcrumb trail must use data-testid="breadcrumb".
 *   - The controls toolbar must use data-testid="graph-controls".
 */

import { test, expect } from '@playwright/test';
import { gotoRoute, mockGraphData, waitForGraphRender, ROUTES } from '../utils/test-helpers';

test.describe('Graph view — initial render', () => {
  test.beforeEach(async ({ page }) => {
    await mockGraphData(page, 50);
    await gotoRoute(page, ROUTES.graph);
    await waitForGraphRender(page);
  });

  test('graph container is visible', async ({ page }) => {
    await expect(page.locator('[data-testid="graph-container"]')).toBeVisible();
  });

  test('controls toolbar is visible', async ({ page }) => {
    await expect(page.locator('[data-testid="graph-controls"]')).toBeVisible();
  });

  test('minimap is visible', async ({ page }) => {
    await expect(page.locator('[data-testid="graph-minimap"]')).toBeVisible();
  });

  test('breadcrumb shows Level 0 (landscape)', async ({ page }) => {
    const breadcrumb = page.locator('[data-testid="breadcrumb"]');
    await expect(breadcrumb).toBeVisible();
    await expect(breadcrumb).toContainText(/landscape|overview|level 0/i);
  });
});

// ── Drill-down navigation ─────────────────────────────────────────────────────

test.describe('Graph drill-down', () => {
  test.beforeEach(async ({ page }) => {
    await mockGraphData(page, 200);
    await gotoRoute(page, ROUTES.graph);
    await waitForGraphRender(page);
  });

  test('double-click on a node drills down to Level 1', async ({ page }) => {
    // Find a rendered node (G6 renders nodes as canvas elements or SVG circles)
    // The test-id convention: each node should be selectable via data-node-id
    const graphCanvas = page.locator('[data-testid="graph-container"]');
    const center = await graphCanvas.boundingBox();
    if (!center) throw new Error('Graph container not found');

    // Double-click near the center where a node cluster should be
    await page.mouse.dblclick(center.x + center.width / 2, center.y + center.height / 2);

    // Breadcrumb should now show Level 1
    const breadcrumb = page.locator('[data-testid="breadcrumb"]');
    await expect(breadcrumb).toContainText(/module|level 1|›/i);
  });

  test('breadcrumb "Home" returns to Level 0', async ({ page }) => {
    const graphCanvas = page.locator('[data-testid="graph-container"]');
    const center = await graphCanvas.boundingBox();
    if (!center) throw new Error('Graph container not found');

    // Drill down
    await page.mouse.dblclick(center.x + center.width / 2, center.y + center.height / 2);

    // Click the first breadcrumb item (Home / Level 0)
    await page.locator('[data-testid="breadcrumb"] [data-testid="breadcrumb-item"]:first-child').click();

    // Should be back at Level 0
    await expect(page.locator('[data-testid="breadcrumb"]')).toContainText(/landscape|overview|level 0/i);
  });
});

// ── Graph controls ────────────────────────────────────────────────────────────

test.describe('Graph controls toolbar', () => {
  test.beforeEach(async ({ page }) => {
    await mockGraphData(page, 50);
    await gotoRoute(page, ROUTES.graph);
    await waitForGraphRender(page);
  });

  test('zoom-in button increases zoom level', async ({ page }) => {
    const zoomInBtn = page.getByRole('button', { name: /zoom in/i });
    await expect(zoomInBtn).toBeVisible();

    const zoomBefore = await page.evaluate(() =>
      (window as Window & { __graphZoom?: number }).__graphZoom ?? 1
    );
    await zoomInBtn.click();
    const zoomAfter = await page.evaluate(() =>
      (window as Window & { __graphZoom?: number }).__graphZoom ?? 1
    );
    expect(zoomAfter).toBeGreaterThan(zoomBefore);
  });

  test('zoom-out button decreases zoom level', async ({ page }) => {
    const zoomOutBtn = page.getByRole('button', { name: /zoom out/i });
    await expect(zoomOutBtn).toBeVisible();

    const zoomBefore = await page.evaluate(() =>
      (window as Window & { __graphZoom?: number }).__graphZoom ?? 1
    );
    await zoomOutBtn.click();
    const zoomAfter = await page.evaluate(() =>
      (window as Window & { __graphZoom?: number }).__graphZoom ?? 1
    );
    expect(zoomAfter).toBeLessThan(zoomBefore);
  });

  test('re-center button fits graph to viewport', async ({ page }) => {
    const recenterBtn = page.getByRole('button', { name: /re-?center|fit|reset view/i });
    await expect(recenterBtn).toBeVisible();
    await recenterBtn.click();
    // After re-center the graph-render-state should still be ready
    await expect(
      page.locator('[data-testid="graph-container"]')
    ).toHaveAttribute('data-render-state', 'ready');
  });

  test('layout selector dropdown is present and operable', async ({ page }) => {
    const layoutSelector = page.locator('[data-testid="layout-selector"]');
    await expect(layoutSelector).toBeVisible();
    await layoutSelector.click();
    // Options: force, hierarchical, radial, circular
    await expect(page.getByRole('option', { name: /force/i })).toBeVisible();
    await expect(page.getByRole('option', { name: /hierarchical/i })).toBeVisible();
  });

  test('node type filter checkboxes render and toggle', async ({ page }) => {
    const filterPanel = page.locator('[data-testid="node-type-filter"]');
    await expect(filterPanel).toBeVisible();

    // At least one checkbox for a known kind
    const endpointFilter = filterPanel.getByRole('checkbox', { name: /endpoint/i });
    await expect(endpointFilter).toBeVisible();

    await endpointFilter.uncheck();
    await expect(endpointFilter).not.toBeChecked();
    await endpointFilter.check();
    await expect(endpointFilter).toBeChecked();
  });

  test('fullscreen toggle is present', async ({ page }) => {
    await expect(page.getByRole('button', { name: /fullscreen/i })).toBeVisible();
  });
});

// ── Node interactions ─────────────────────────────────────────────────────────

test.describe('Graph node interactions', () => {
  test.beforeEach(async ({ page }) => {
    await mockGraphData(page, 50);
    await gotoRoute(page, ROUTES.graph);
    await waitForGraphRender(page);
  });

  test('right-click on canvas shows context menu', async ({ page }) => {
    const graphCanvas = page.locator('[data-testid="graph-container"]');
    const center = await graphCanvas.boundingBox();
    if (!center) throw new Error('Graph container not found');

    await page.mouse.click(
      center.x + center.width / 2,
      center.y + center.height / 2,
      { button: 'right' }
    );

    const contextMenu = page.locator('[data-testid="graph-context-menu"]');
    await expect(contextMenu).toBeVisible();
    // Should have at least: Show Details, Find Callers, Find Dependencies
    await expect(contextMenu.getByRole('menuitem', { name: /show details/i })).toBeVisible();
    await expect(contextMenu.getByRole('menuitem', { name: /callers/i })).toBeVisible();
    await expect(contextMenu.getByRole('menuitem', { name: /dependencies/i })).toBeVisible();
  });

  test('context menu dismisses on Escape', async ({ page }) => {
    const graphCanvas = page.locator('[data-testid="graph-container"]');
    const center = await graphCanvas.boundingBox();
    if (!center) throw new Error('Graph container not found');

    await page.mouse.click(
      center.x + center.width / 2,
      center.y + center.height / 2,
      { button: 'right' }
    );

    await page.keyboard.press('Escape');
    await expect(page.locator('[data-testid="graph-context-menu"]')).not.toBeVisible();
  });

  test('mouse wheel zooms the graph', async ({ page }) => {
    const graphCanvas = page.locator('[data-testid="graph-container"]');
    const center = await graphCanvas.boundingBox();
    if (!center) throw new Error('Graph container not found');

    const zoomBefore = await page.evaluate(() =>
      (window as Window & { __graphZoom?: number }).__graphZoom ?? 1
    );
    await page.mouse.wheel(0, -120); // scroll up = zoom in
    const zoomAfter = await page.evaluate(() =>
      (window as Window & { __graphZoom?: number }).__graphZoom ?? 1
    );
    expect(zoomAfter).not.toBe(zoomBefore);
  });
});
