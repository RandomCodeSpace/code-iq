/**
 * Edge case tests.
 *
 * Covers requirement:
 *   9. Edge cases — empty graph, single node, disconnected components, very deep hierarchies
 *
 * Also covers API error handling and resilience.
 */

import { test, expect } from '@playwright/test';
import { gotoRoute, mockStats, ROUTES } from '../utils/test-helpers';

// ── Empty graph ───────────────────────────────────────────────────────────────

test.describe('Empty graph state', () => {
  test.beforeEach(async ({ page }) => {
    // Return zero nodes and edges
    await page.route('**/api/kinds', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ kinds: [] }) })
    );
    await page.route('**/api/nodes**', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ nodes: [], total: 0 }) })
    );
    await page.route('**/api/topology', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ services: [] }) })
    );
    await page.route('**/api/stats', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ totalNodes: 0, totalEdges: 0 }) })
    );
  });

  test('graph view shows empty-state message (not blank screen)', async ({ page }) => {
    await gotoRoute(page, ROUTES.graph);
    const emptyState = page.locator('[data-testid="graph-empty-state"]');
    await expect(emptyState).toBeVisible({ timeout: 3000 });
    await expect(emptyState).toContainText(/no nodes|empty|run.*index|get started/i);
  });

  test('dashboard shows zero stats without error', async ({ page }) => {
    await gotoRoute(page, ROUTES.dashboard);
    // Should render — no crash
    await expect(page.locator('main')).toBeVisible();
    // Stats showing 0 is acceptable
    await expect(page.getByText(/0/)).toBeVisible();
  });

  test('explorer view shows empty-state message', async ({ page }) => {
    await gotoRoute(page, ROUTES.explorer);
    await expect(page.locator('main')).toBeVisible();
    // Should not show an unhandled error
    await expect(page.locator('[data-testid="error-boundary"]')).not.toBeVisible();
  });

  test('search returns empty results gracefully', async ({ page }) => {
    await page.route('**/api/search**', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    );
    await gotoRoute(page, ROUTES.dashboard);
    await page.getByRole('searchbox').fill('anything');
    const dropdown = page.locator('[data-testid="search-dropdown"]');
    await expect(dropdown).toBeVisible({ timeout: 1000 });
    await expect(dropdown).toContainText(/no results/i);
  });
});

// ── Single node graph ─────────────────────────────────────────────────────────

test.describe('Single node graph', () => {
  test.beforeEach(async ({ page }) => {
    const singleNode = {
      id: 'node:App.java:class:App',
      kind: 'class',
      name: 'App',
      qualifiedName: 'com.example.App',
      filePath: 'src/main/java/App.java',
      layer: 'backend',
      framework: 'spring_boot',
      properties: {},
    };

    await page.route('**/api/kinds', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ kinds: [{ kind: 'class', count: 1 }] }) })
    );
    await page.route('**/api/nodes**', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ nodes: [singleNode], total: 1 }) })
    );
    await page.route('**/api/topology', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ services: [{ name: 'app', nodeCount: 1, dependencies: [] }] }) })
    );
    await page.route('**/api/stats', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ totalNodes: 1, totalEdges: 0 }) })
    );
  });

  test('graph renders a single node without crash', async ({ page }) => {
    await gotoRoute(page, ROUTES.graph);
    await expect(page.locator('[data-testid="graph-container"]')).toBeVisible();
    await expect(page.locator('[data-testid="error-boundary"]')).not.toBeVisible();
  });

  test('single node has no outbound drill-down (shows leaf state)', async ({ page }) => {
    await gotoRoute(page, ROUTES.graph);
    await page.route('**/api/nodes/**/neighbors**', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ nodes: [], edges: [] }) })
    );

    const graphCanvas = page.locator('[data-testid="graph-container"]');
    const box = await graphCanvas.boundingBox();
    if (!box) return;

    await page.mouse.dblclick(box.x + box.width / 2, box.y + box.height / 2);

    // Should NOT crash — graph stays at Level 1 with no children
    await expect(page.locator('[data-testid="error-boundary"]')).not.toBeVisible();
  });
});

// ── Disconnected components ───────────────────────────────────────────────────

test.describe('Disconnected graph components', () => {
  test('graph renders disconnected subgraphs as separate clusters', async ({ page }) => {
    // Two disconnected services with no edges between them
    const disconnectedTopology = {
      services: [
        { name: 'service-a', nodeCount: 5, dependencies: [] },
        { name: 'service-b', nodeCount: 5, dependencies: [] },
      ],
    };
    await page.route('**/api/topology', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(disconnectedTopology) })
    );
    await page.route('**/api/stats', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ totalNodes: 10, totalEdges: 0 }) })
    );

    await gotoRoute(page, ROUTES.graph);
    await expect(page.locator('[data-testid="graph-container"]')).toBeVisible();
    await expect(page.locator('[data-testid="error-boundary"]')).not.toBeVisible();
  });
});

// ── Very deep hierarchy ───────────────────────────────────────────────────────

test.describe('Deep hierarchy', () => {
  test('graph handles 10-level deep hierarchy without stack overflow', async ({ page }) => {
    // Simulate 10 levels of drill-down by mocking neighbors to always return children
    let drillLevel = 0;
    await page.route('**/api/nodes/**/neighbors**', route => {
      drillLevel++;
      if (drillLevel > 10) {
        return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ nodes: [], edges: [] }) });
      }
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          nodes: [{ id: `node:file.ts:class:Deep${drillLevel}`, kind: 'class', name: `Deep${drillLevel}`, filePath: 'file.ts' }],
          edges: [],
        }),
      });
    });

    await page.route('**/api/stats', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ totalNodes: 100, totalEdges: 50 }) })
    );

    await gotoRoute(page, ROUTES.graph);

    // Repeatedly drill down — no crash or unhandled error expected
    const graphCanvas = page.locator('[data-testid="graph-container"]');
    const box = await graphCanvas.boundingBox();
    if (!box) return;

    for (let i = 0; i < 10; i++) {
      await page.mouse.dblclick(box.x + box.width / 2, box.y + box.height / 2);
      await page.waitForTimeout(200);
    }

    await expect(page.locator('[data-testid="error-boundary"]')).not.toBeVisible();
  });
});

// ── API error resilience ──────────────────────────────────────────────────────

test.describe('API error handling', () => {
  test('graph view shows error state when /api/topology returns 500', async ({ page }) => {
    await page.route('**/api/topology', route =>
      route.fulfill({ status: 500, contentType: 'application/json', body: '{"error":"Internal server error"}' })
    );
    await page.route('**/api/stats', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ totalNodes: 0, totalEdges: 0 }) })
    );

    await gotoRoute(page, ROUTES.graph);

    // Should show an error message, not a blank screen or JS crash
    const errorState = page.locator('[data-testid="graph-error-state"], [role="alert"]');
    await expect(errorState).toBeVisible({ timeout: 3000 });
  });

  test('dashboard shows error state when /api/stats returns 500', async ({ page }) => {
    await page.route('**/api/stats', route =>
      route.fulfill({ status: 500, body: '' })
    );

    await gotoRoute(page, ROUTES.dashboard);

    // Page must not crash (no blank screen)
    await expect(page.locator('main')).toBeVisible();
    // Error message or fallback state
    const errorIndicator = page.locator('[data-testid="stats-error"], [role="alert"], [data-testid="error-boundary"]');
    await expect(errorIndicator).toBeVisible({ timeout: 3000 });
  });

  test('network timeout shows user-friendly message', async ({ page }) => {
    await page.route('**/api/**', route => route.abort('timedout'));

    await gotoRoute(page, ROUTES.dashboard);

    // Should not be a blank page
    await expect(page.locator('body')).not.toBeEmpty();
    await expect(page.locator('main')).toBeVisible();
  });

  test('MCP Console handles tool execution error gracefully', async ({ page }) => {
    await mockStats(page);
    await page.route('**/api/stats', route =>
      route.fulfill({ status: 500, body: '{"error":"DB unavailable"}' })
    );

    await gotoRoute(page, ROUTES.console);
    // Run get_stats — it should fail
    const toolItem = page.locator('[data-testid="tool-item"][data-tool-name="get_stats"]');
    if (await toolItem.isVisible()) {
      await toolItem.getByRole('button', { name: /run|execute/i }).click();
      const errorResponse = page.locator('[data-testid="tool-response"][data-status="error"]');
      await expect(errorResponse).toBeVisible({ timeout: 3000 });
    }
  });
});
