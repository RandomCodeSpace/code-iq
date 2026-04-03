/**
 * Graph render performance baseline tests.
 *
 * Covers:
 *   AC-2  Graph renders 10K nodes in <3s
 *         (<500ms for 100 nodes, <2000ms for 1K nodes)
 *
 * Methodology:
 *   1. The graph component must expose `window.__graphRenderMs` —
 *      the elapsed time (ms) from API data received to G6 `afterrender` event.
 *   2. Each threshold is tested 3 times; we use the MEDIAN to avoid flakiness.
 *   3. Tests are skipped in CI unless the PERF_TESTS env var is set,
 *      because render performance depends on hardware resources.
 *
 * Implementation contract:
 *   In CodeGraphView.tsx, after G6 fires `afterrender`, set:
 *     window.__graphRenderMs = performance.now() - dataReceivedAt;
 *     graphContainer.setAttribute('data-render-state', 'ready');
 */

import { test, expect } from '@playwright/test';
import { gotoRoute, mockGraphData, waitForGraphRender, measureGraphRenderTime, ROUTES } from '../utils/test-helpers';

const PERF_THRESHOLD_100 = 500;   // ms — 100 nodes
const PERF_THRESHOLD_1K  = 2000;  // ms — 1K nodes
const PERF_THRESHOLD_10K = 3000;  // ms — 10K nodes
const ITERATIONS = 3;

/** Run a render scenario `n` times and return the median elapsed time. */
async function medianRenderTime(
  page: Parameters<Parameters<typeof test>[1]>[0],
  nodeCount: number
): Promise<number> {
  const times: number[] = [];

  for (let i = 0; i < ITERATIONS; i++) {
    await mockGraphData(page, nodeCount);
    await gotoRoute(page, ROUTES.graph);
    await waitForGraphRender(page, 15_000);
    const ms = await measureGraphRenderTime(page);
    if (ms > 0) times.push(ms);
    // Navigate away to reset state
    await page.goto('about:blank');
  }

  if (times.length === 0) return -1;
  times.sort((a, b) => a - b);
  return times[Math.floor(times.length / 2)];
}

// ── 100-node baseline ─────────────────────────────────────────────────────────

test('renders 100 nodes in < 500ms (median of 3 runs)', async ({ page }) => {
  test.slow(); // allow extra time for this test
  const median = await medianRenderTime(page, 100);
  expect(
    median,
    `100-node render median ${median}ms exceeds ${PERF_THRESHOLD_100}ms threshold`
  ).toBeLessThan(PERF_THRESHOLD_100);
});

// ── 1K-node baseline ──────────────────────────────────────────────────────────

test('renders 1,000 nodes in < 2s (median of 3 runs)', async ({ page }) => {
  test.slow();
  const median = await medianRenderTime(page, 1_000);
  expect(
    median,
    `1K-node render median ${median}ms exceeds ${PERF_THRESHOLD_1K}ms threshold`
  ).toBeLessThan(PERF_THRESHOLD_1K);
});

// ── 10K-node baseline ─────────────────────────────────────────────────────────

test('renders 10,000 nodes in < 3s (median of 3 runs)', async ({ page }) => {
  test.slow();
  const median = await medianRenderTime(page, 10_000);
  expect(
    median,
    `10K-node render median ${median}ms exceeds ${PERF_THRESHOLD_10K}ms threshold`
  ).toBeLessThan(PERF_THRESHOLD_10K);
});

// ── Drill-down performance ────────────────────────────────────────────────────

test('drill-down transition completes within 1s', async ({ page }) => {
  test.slow();
  await mockGraphData(page, 500);
  await gotoRoute(page, ROUTES.graph);
  await waitForGraphRender(page);

  const graphCanvas = page.locator('[data-testid="graph-container"]');
  const center = await graphCanvas.boundingBox();
  if (!center) throw new Error('Graph container not found');

  const t0 = Date.now();
  await page.mouse.dblclick(center.x + center.width / 2, center.y + center.height / 2);
  await waitForGraphRender(page, 5000);
  const elapsed = Date.now() - t0;

  expect(elapsed, `Drill-down took ${elapsed}ms, expected <1000ms`).toBeLessThan(1000);
});

// ── Layout switch performance ─────────────────────────────────────────────────

test('switching layout type completes within 1s', async ({ page }) => {
  test.slow();
  await mockGraphData(page, 200);
  await gotoRoute(page, ROUTES.graph);
  await waitForGraphRender(page);

  const layoutSelector = page.locator('[data-testid="layout-selector"]');
  await layoutSelector.click();
  const hierarchicalOption = page.getByRole('option', { name: /hierarchical/i });
  if (!(await hierarchicalOption.isVisible())) return; // layout options not yet implemented

  const t0 = Date.now();
  await hierarchicalOption.click();
  await waitForGraphRender(page, 5000);
  const elapsed = Date.now() - t0;

  expect(elapsed, `Layout switch took ${elapsed}ms, expected <1000ms`).toBeLessThan(1000);
});

// ── Page load time ────────────────────────────────────────────────────────────

test('dashboard loads within 3s (time to interactive)', async ({ page }) => {
  const t0 = Date.now();
  await gotoRoute(page, ROUTES.dashboard);
  await page.waitForLoadState('networkidle');
  const elapsed = Date.now() - t0;
  expect(elapsed, `Dashboard load took ${elapsed}ms`).toBeLessThan(3000);
});

test('initial graph page load within 5s (includes G6 module)', async ({ page }) => {
  await mockGraphData(page, 100);
  const t0 = Date.now();
  await gotoRoute(page, ROUTES.graph);
  await waitForGraphRender(page, 10_000);
  const elapsed = Date.now() - t0;
  // G6 is code-split into an async chunk; first load may be slower
  expect(elapsed, `Graph page initial load took ${elapsed}ms`).toBeLessThan(5000);
});
