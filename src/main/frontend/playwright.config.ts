/// <reference types="node" />
import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright E2E test configuration for Code IQ UI Redesign (Phase 7).
 *
 * Prerequisites:
 *   - A pre-built CLI jar under ../../../target/code-iq-*-cli.jar
 *     (run `mvn -DskipTests package` from the repo root if missing).
 *   - A pre-populated fixture at <repo-root>/.seeds/spring-petclinic
 *     with .code-iq/cache + .code-iq/graph populated by
 *     `./scripts/baseline/run-pipeline.sh spring-petclinic`. The webServer
 *     block below boots `code-iq serve` against this fixture automatically.
 *   - Set `BASE_URL` / `E2E_FIXTURE` to override defaults when running against
 *     a different backend or fixture.
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

  // Boot the real code-iq backend against a pre-enriched fixture before any
  // spec runs. Skipped if BASE_URL points elsewhere (developer has their own
  // backend) or if a server is already listening on the target port locally.
  webServer: process.env.BASE_URL ? undefined : {
    command: [
      'bash -c',
      // Use shell so the target/*-cli.jar glob expands. `exec` hands the PID
      // to Playwright so it can kill the process cleanly on test teardown.
      `"exec java -jar ../../../target/code-iq-*-cli.jar serve `
        + `${process.env.E2E_FIXTURE ?? '../../../.seeds/spring-petclinic'} `
        + `--port ${process.env.E2E_PORT ?? '8080'}"`,
    ].join(' '),
    // /actuator/health is not a reliable readiness signal today (see
    // BASELINE.md §GraphHealthIndicator). /api/stats returns 200 iff the
    // server has finished starting and the graph has loaded.
    url: `http://localhost:${process.env.E2E_PORT ?? '8080'}/api/stats`,
    timeout: 120_000,
    reuseExistingServer: !process.env.CI,
    stdout: 'pipe',
    stderr: 'pipe',
  },

  use: {
    baseURL: process.env.BASE_URL ?? `http://localhost:${process.env.E2E_PORT ?? '8080'}`,
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
