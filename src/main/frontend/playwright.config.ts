/// <reference types="node" />
import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E test configuration for Code IQ UI Redesign (Phase 7).
 *
 * Prerequisites:
 *   - `code-iq serve` running on localhost:8080 (Neo4j graph loaded)
 *   - Or use `webServer` below to start the Vite dev server pointing at a real backend
 *
 * Run all tests:       npm run test:e2e
 * Run headed:          npm run test:e2e:headed
 * Show HTML report:    npm run test:e2e:report
 */
export default defineConfig({
  testDir: './tests/e2e',
  // Use the test-specific tsconfig that includes @types/node
  tsconfig: './tsconfig.test.json',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [['html', { outputFolder: 'playwright-report' }], ['line']],

  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:8080',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  // Performance threshold constants (ms) shared via env so specs can read them
  // Actual assertions live in performance.spec.ts
  //   PERF_THRESHOLD_100    = 500
  //   PERF_THRESHOLD_1K     = 2000
  //   PERF_THRESHOLD_10K    = 3000

  projects: [
    // P0 — required for release sign-off
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'firefox',
      use: { ...devices['Desktop Firefox'] },
    },

    // P1 — run in CI when available
    {
      name: 'edge',
      use: { ...devices['Desktop Edge'], channel: 'msedge' },
    },
    {
      name: 'webkit',
      use: { ...devices['Desktop Safari'] },
    },

    // Responsive breakpoints (chromium only — layout logic is shared)
    {
      name: 'desktop-1920',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1920, height: 1080 } },
      testMatch: '**/responsive.spec.ts',
    },
    {
      name: 'laptop-1440',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } },
      testMatch: '**/responsive.spec.ts',
    },
    {
      name: 'tablet-768',
      use: { ...devices['Desktop Chrome'], viewport: { width: 768, height: 1024 } },
      testMatch: '**/responsive.spec.ts',
    },
  ],
});
