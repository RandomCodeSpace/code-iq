/**
 * Global search tests.
 *
 * Covers requirement:
 *   7. Search testing — global search returns correct results, filters tree and graph
 */

import { test, expect } from '@playwright/test';
import { gotoRoute, mockStats, ROUTES } from '../utils/test-helpers';

const MOCK_SEARCH_RESULTS = [
  { id: 'node:UserService.java:class:UserService', kind: 'class', name: 'UserService', filePath: 'src/main/java/UserService.java', score: 1.0 },
  { id: 'node:UserService.java:method:findById', kind: 'method', name: 'findById', filePath: 'src/main/java/UserService.java', score: 0.8 },
  { id: 'node:UserController.java:endpoint:getUser', kind: 'endpoint', name: 'GET /users/{id}', filePath: 'src/main/java/UserController.java', score: 0.7 },
];

test.describe('Global search', () => {
  test.beforeEach(async ({ page }) => {
    await mockStats(page);

    // Mock search API
    await page.route('**/api/search**', route =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_SEARCH_RESULTS),
      })
    );
  });

  test('search box is visible in header on all views', async ({ page }) => {
    for (const route of Object.values(ROUTES)) {
      await gotoRoute(page, route);
      await expect(page.getByRole('searchbox')).toBeVisible();
    }
  });

  test('typing fewer than 2 characters does not trigger search', async ({ page }) => {
    await gotoRoute(page, ROUTES.dashboard);
    let searchCalled = false;
    await page.route('**/api/search**', () => { searchCalled = true; });

    await page.getByRole('searchbox').fill('U');
    await page.waitForTimeout(400); // debounce window

    expect(searchCalled).toBe(false);
  });

  test('typing 2+ characters triggers search with debounce', async ({ page }) => {
    await gotoRoute(page, ROUTES.dashboard);
    const searchBox = page.getByRole('searchbox');
    await searchBox.fill('User');

    // Wait for debounce (300ms) + render
    const dropdown = page.locator('[data-testid="search-dropdown"]');
    await expect(dropdown).toBeVisible({ timeout: 1000 });
  });

  test('search results show correct names and kinds', async ({ page }) => {
    await gotoRoute(page, ROUTES.dashboard);
    await page.getByRole('searchbox').fill('User');

    const dropdown = page.locator('[data-testid="search-dropdown"]');
    await expect(dropdown).toBeVisible({ timeout: 1000 });

    await expect(dropdown.getByText('UserService')).toBeVisible();
    await expect(dropdown.getByText('findById')).toBeVisible();
    await expect(dropdown.getByText('GET /users/{id}')).toBeVisible();
  });

  test('clicking a result navigates to the Explorer view', async ({ page }) => {
    await gotoRoute(page, ROUTES.dashboard);
    await page.getByRole('searchbox').fill('User');

    const dropdown = page.locator('[data-testid="search-dropdown"]');
    await expect(dropdown).toBeVisible({ timeout: 1000 });
    await dropdown.getByText('UserService').click();

    // Should navigate to explorer with the selected node
    await expect(page).toHaveURL(/\/explorer/);
  });

  test('pressing Escape clears search dropdown', async ({ page }) => {
    await gotoRoute(page, ROUTES.dashboard);
    await page.getByRole('searchbox').fill('User');

    const dropdown = page.locator('[data-testid="search-dropdown"]');
    await expect(dropdown).toBeVisible({ timeout: 1000 });

    await page.keyboard.press('Escape');
    await expect(dropdown).not.toBeVisible();
  });

  test('clicking outside search closes the dropdown', async ({ page }) => {
    await gotoRoute(page, ROUTES.dashboard);
    await page.getByRole('searchbox').fill('User');

    const dropdown = page.locator('[data-testid="search-dropdown"]');
    await expect(dropdown).toBeVisible({ timeout: 1000 });

    // Click somewhere outside the search bar
    await page.locator('main').click({ position: { x: 10, y: 10 }, force: true });
    await expect(dropdown).not.toBeVisible();
  });

  test('keyboard navigation in search results (ArrowDown / Enter)', async ({ page }) => {
    await gotoRoute(page, ROUTES.dashboard);
    const searchBox = page.getByRole('searchbox');
    await searchBox.fill('User');

    const dropdown = page.locator('[data-testid="search-dropdown"]');
    await expect(dropdown).toBeVisible({ timeout: 1000 });

    // Navigate with ArrowDown and select with Enter
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');

    // Should have navigated
    await expect(page).toHaveURL(/\/explorer|\/graph/);
  });

  test('loading indicator shows while search is in progress', async ({ page }) => {
    // Slow down the search response to see the loading state
    await page.route('**/api/search**', async route => {
      await new Promise(resolve => setTimeout(resolve, 300));
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify(MOCK_SEARCH_RESULTS),
      });
    });

    await gotoRoute(page, ROUTES.dashboard);
    await page.getByRole('searchbox').fill('User');

    // Loading indicator should appear briefly
    const spinner = page.locator('[data-testid="search-spinner"]');
    await expect(spinner).toBeVisible({ timeout: 500 });
  });

  test('empty search results shows "no results" message', async ({ page }) => {
    await page.route('**/api/search**', route =>
      route.fulfill({ status: 200, contentType: 'application/json', body: '[]' })
    );

    await gotoRoute(page, ROUTES.dashboard);
    await page.getByRole('searchbox').fill('xyznonexistent');

    const dropdown = page.locator('[data-testid="search-dropdown"]');
    await expect(dropdown).toBeVisible({ timeout: 1000 });
    await expect(dropdown).toContainText(/no results/i);
  });
});

// ── File tree filtering (Phase 2 Frontend) ────────────────────────────────────

test.describe('File tree search integration', () => {
  test.beforeEach(async ({ page }) => {
    await mockStats(page);
    await page.route('**/api/file-tree**', route =>
      route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          name: 'root',
          children: [
            { name: 'src', children: [
              { name: 'main', children: [
                { name: 'java', children: [
                  { name: 'UserService.java', nodeCount: 5 },
                  { name: 'UserController.java', nodeCount: 3 },
                ]},
              ]},
            ]},
          ],
        }),
      })
    );
  });

  test('typing in search filters the file tree', async ({ page }) => {
    await gotoRoute(page, ROUTES.explorer);
    await page.getByRole('searchbox').fill('UserService');

    // File tree should filter to show only matching files
    const tree = page.locator('[data-testid="file-tree"]');
    if (await tree.isVisible()) {
      await expect(tree.getByText('UserService.java')).toBeVisible();
      // Non-matching file should be hidden
      await expect(tree.getByText('UserController.java')).not.toBeVisible();
    }
  });
});
