# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: graph.spec.ts >> Graph view — initial render >> graph container is visible
- Location: tests/e2e/graph.spec.ts:26:3

# Error details

```
Test timeout of 30000ms exceeded while running "beforeEach" hook.
```

```
TimeoutError: page.waitForSelector: Timeout 30000ms exceeded.
Call log:
  - waiting for locator('main') to be visible

```

# Test source

```ts
  1   | /// <reference types="node" />
  2   | import { type Page, expect } from '@playwright/test';
  3   | import { readFileSync } from 'node:fs';
  4   | import { resolve } from 'node:path';
  5   | 
  6   | // ── Route helpers ────────────────────────────────────────────────────────────
  7   | 
  8   | export const ROUTES = {
  9   |   dashboard: '/',
  10  |   graph: '/graph',
  11  |   explorer: '/explorer',
  12  |   console: '/console',
  13  |   apiDocs: '/api-docs',
  14  | } as const;
  15  | 
  16  | export type AppRoute = (typeof ROUTES)[keyof typeof ROUTES];
  17  | 
  18  | /**
  19  |  * Intercept the HTML shell served by Spring Boot and replace it with the
  20  |  * current on-disk version. The running JAR may contain a stale index.html
  21  |  * (built before the last frontend rebuild), causing it to load an old
  22  |  * JS bundle that crashes before React mounts.
  23  |  *
  24  |  * Bug: STALE_BUNDLE — tracked in RAN-80 (filed separately).
  25  |  */
  26  | export async function patchIndexHtml(page: Page) {
  27  |   // process.cwd() is the frontend dir when running `npx playwright test`
  28  |   const diskHtml = readFileSync(
  29  |     resolve(process.cwd(), '../resources/static/index.html'),
  30  |     'utf-8',
  31  |   );
  32  |   // Intercept the SPA shell route (all navigation routes return the same HTML)
  33  |   await page.route('**/*', async (route) => {
  34  |     const req = route.request();
  35  |     const url = req.url();
  36  |     // Only intercept HTML document requests (the SPA shell), not API/asset calls
  37  |     if (
  38  |       req.resourceType() === 'document' &&
  39  |       !url.includes('/api/') &&
  40  |       !url.includes('/assets/') &&
  41  |       !url.includes('/swagger') &&
  42  |       !url.includes('/v3/')
  43  |     ) {
  44  |       await route.fulfill({
  45  |         status: 200,
  46  |         contentType: 'text/html',
  47  |         body: diskHtml,
  48  |       });
  49  |     } else {
  50  |       await route.continue();
  51  |     }
  52  |   });
  53  | }
  54  | 
  55  | /** Navigate to a route and wait for the main content area to be visible. */
  56  | export async function gotoRoute(page: Page, route: AppRoute) {
  57  |   await patchIndexHtml(page);
  58  |   await page.goto(route);
  59  |   // Wait for React to hydrate (main rendered by Layout component)
> 60  |   await page.waitForSelector('main', { state: 'visible', timeout: 30000 });
      |              ^ TimeoutError: page.waitForSelector: Timeout 30000ms exceeded.
  61  | }
  62  | 
  63  | // ── Theme helpers ────────────────────────────────────────────────────────────
  64  | 
  65  | /** Returns the current theme: 'dark' | 'light'. */
  66  | export async function getTheme(page: Page): Promise<'dark' | 'light'> {
  67  |   const cls = await page.locator('html').getAttribute('class') ?? '';
  68  |   return cls.includes('dark') ? 'dark' : 'light';
  69  | }
  70  | 
  71  | /** Click the theme toggle and wait for the class to flip. */
  72  | export async function toggleTheme(page: Page) {
  73  |   const before = await getTheme(page);
  74  |   // Theme toggle button — uses aria-label or data-testid set by the component
  75  |   await page.getByRole('button', { name: /toggle theme|switch theme|dark mode|light mode/i }).click();
  76  |   await expect(page.locator('html')).toHaveClass(before === 'dark' ? /light/ : /dark/, { timeout: 2000 });
  77  | }
  78  | 
  79  | // ── API mock helpers ─────────────────────────────────────────────────────────
  80  | 
  81  | /** Seed the `/api/stats` mock for deterministic dashboard tests. */
  82  | export async function mockStats(page: Page, nodeCount = 1234, edgeCount = 5678) {
  83  |   await page.route('**/api/stats', route =>
  84  |     route.fulfill({
  85  |       status: 200,
  86  |       contentType: 'application/json',
  87  |       body: JSON.stringify({
  88  |         totalNodes: nodeCount,
  89  |         totalEdges: edgeCount,
  90  |         nodesByKind: { endpoint: 10, class: 20, method: 30 },
  91  |         edgesByKind: { calls: 100, depends_on: 50 },
  92  |         languages: { java: 500, typescript: 200 },
  93  |         frameworks: { spring_boot: 300 },
  94  |         layers: { backend: 600, frontend: 200, infra: 100, shared: 50, unknown: 284 },
  95  |       }),
  96  |     })
  97  |   );
  98  | }
  99  | 
  100 | /**
  101 |  * Generate a synthetic node list for performance/stress tests.
  102 |  * Returns a NodesListResponse-shaped object.
  103 |  */
  104 | export function generateNodeList(count: number) {
  105 |   const nodes = Array.from({ length: count }, (_, i) => ({
  106 |     id: `node:file${i % 100}.ts:class:Class${i}`,
  107 |     kind: ['class', 'method', 'endpoint', 'entity', 'function'][i % 5],
  108 |     name: `Symbol${i}`,
  109 |     qualifiedName: `com.example.Symbol${i}`,
  110 |     filePath: `src/file${i % 100}.ts`,
  111 |     layer: 'backend',
  112 |     framework: null,
  113 |     properties: {},
  114 |   }));
  115 |   return { nodes, total: count, offset: 0, limit: count };
  116 | }
  117 | 
  118 | /** Seed the `/api/kinds` + `/api/nodes` endpoints with synthetic data. */
  119 | export async function mockGraphData(page: Page, nodeCount: number) {
  120 |   const data = generateNodeList(nodeCount);
  121 | 
  122 |   await page.route('**/api/kinds', route =>
  123 |     route.fulfill({
  124 |       status: 200,
  125 |       contentType: 'application/json',
  126 |       body: JSON.stringify({
  127 |         kinds: [
  128 |           { kind: 'class', count: Math.floor(nodeCount * 0.3) },
  129 |           { kind: 'method', count: Math.floor(nodeCount * 0.3) },
  130 |           { kind: 'endpoint', count: Math.floor(nodeCount * 0.15) },
  131 |           { kind: 'entity', count: Math.floor(nodeCount * 0.15) },
  132 |           { kind: 'function', count: Math.floor(nodeCount * 0.1) },
  133 |         ],
  134 |       }),
  135 |     })
  136 |   );
  137 | 
  138 |   await page.route('**/api/nodes**', route =>
  139 |     route.fulfill({
  140 |       status: 200,
  141 |       contentType: 'application/json',
  142 |       body: JSON.stringify(data),
  143 |     })
  144 |   );
  145 | 
  146 |   await page.route('**/api/topology', route =>
  147 |     route.fulfill({
  148 |       status: 200,
  149 |       contentType: 'application/json',
  150 |       body: JSON.stringify({
  151 |         services: [
  152 |           { name: 'api-service', nodeCount: Math.floor(nodeCount / 3), dependencies: ['db-service'] },
  153 |           { name: 'db-service', nodeCount: Math.floor(nodeCount / 3), dependencies: [] },
  154 |           { name: 'frontend-service', nodeCount: Math.floor(nodeCount / 3), dependencies: ['api-service'] },
  155 |         ],
  156 |       }),
  157 |     })
  158 |   );
  159 | }
  160 | 
```