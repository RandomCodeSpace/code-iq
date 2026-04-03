import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';

/* ── Mock helpers ─────────────────────────────────────────────────── */

const MOCK_FILE_TREE = {
  name: 'root',
  path: '',
  type: 'directory',
  nodeCount: 100,
  children: [
    {
      name: 'src',
      path: 'src',
      type: 'directory',
      nodeCount: 80,
      children: [
        {
          name: 'main',
          path: 'src/main',
          type: 'directory',
          nodeCount: 60,
          children: [
            {
              name: 'App.tsx',
              path: 'src/main/App.tsx',
              type: 'file',
              nodeCount: 12,
            },
            {
              name: 'index.ts',
              path: 'src/main/index.ts',
              type: 'file',
              nodeCount: 3,
            },
          ],
        },
        {
          name: 'components',
          path: 'src/components',
          type: 'directory',
          nodeCount: 20,
          children: [
            {
              name: 'Button.tsx',
              path: 'src/components/Button.tsx',
              type: 'file',
              nodeCount: 5,
            },
          ],
        },
      ],
    },
    {
      name: 'pom.xml',
      path: 'pom.xml',
      type: 'file',
      nodeCount: 2,
    },
  ],
};

async function mockFileTree(page: Page) {
  await page.route('**/api/file-tree**', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(MOCK_FILE_TREE),
    }),
  );
}

async function mockMinimalApis(page: Page) {
  await page.route('**/api/stats**', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ node_count: 100, edge_count: 200, nodes_by_kind: {}, nodes_by_layer: {} }),
    }),
  );
  await page.route('**/api/kinds**', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ kinds: [{ kind: 'class', count: 50 }, { kind: 'method', count: 50 }], total: 100 }),
    }),
  );
  await page.route('**/api/nodes**', route =>
    route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ nodes: [], total: 0, offset: 0, limit: 50 }),
    }),
  );
}

/* ── Tests ────────────────────────────────────────────────────────── */

test.describe('Project File Tree', () => {
  test.beforeEach(async ({ page }) => {
    await mockFileTree(page);
    await mockMinimalApis(page);
  });

  test('renders file tree in sidebar', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');
    await expect(page.getByText('Project Files')).toBeVisible();
    await expect(page.getByRole('tree', { name: 'Project file tree' })).toBeVisible();
  });

  test('shows root directory expanded by default', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');
    // src directory should be visible (root auto-expanded)
    await expect(page.getByText('src')).toBeVisible();
    // pom.xml should be visible (top-level file)
    await expect(page.getByText('pom.xml')).toBeVisible();
  });

  test('expands directory on click', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');

    // 'main' subdirectory is inside 'src' which is inside root
    // Click 'src' to expand it
    await page.getByText('src').click();
    await expect(page.getByText('main')).toBeVisible();
    await expect(page.getByText('components')).toBeVisible();
  });

  test('collapses expanded directory on second click', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');

    // Click src to expand
    await page.getByText('src').click();
    await expect(page.getByText('main')).toBeVisible();

    // Click src again to collapse
    await page.getByText('src').click();
    await expect(page.getByText('main')).not.toBeVisible();
  });

  test('filters tree with search input', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');

    // Expand src to make files visible
    await page.getByText('src').click();
    await page.getByText('main').click();

    const searchInput = page.getByPlaceholder('Filter files…');
    await searchInput.fill('App');

    // Only App.tsx should be visible, not index.ts
    await expect(page.getByText('App.tsx')).toBeVisible();
    await expect(page.getByText('index.ts')).not.toBeVisible();
  });

  test('clears search with X button', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');

    const searchInput = page.getByPlaceholder('Filter files…');
    await searchInput.fill('App');

    await page.getByRole('button', { name: 'Clear filter' }).click();
    await expect(searchInput).toHaveValue('');
  });

  test('shows "no match" message for unmatched query', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');

    await page.getByPlaceholder('Filter files…').fill('xyznotfound');
    await expect(page.getByText(/No files match/)).toBeVisible();
  });

  test('shows node count badges', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');

    // pom.xml has nodeCount: 2, should show badge
    const pomRow = page.getByTestId('tree-node-pom.xml');
    await expect(pomRow).toContainText('2');
  });

  test('navigates to graph view on file click', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');

    // Click pom.xml (visible at root level)
    await page.getByTestId('tree-node-pom.xml').click();

    await expect(page).toHaveURL(/\/graph/);
    await expect(page.getByTestId('file-filter-badge')).toBeVisible();
    await expect(page.getByTestId('file-filter-badge')).toContainText('pom.xml');
  });

  test('keyboard navigation: ArrowDown moves focus', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');

    // Focus the tree
    const tree = page.getByRole('tree', { name: 'Project file tree' });
    await tree.press('ArrowDown');
    // Check that a treeitem receives focus
    const focused = page.locator('[role="treeitem"]:focus');
    await expect(focused).toHaveCount(1);
  });

  test('keyboard navigation: Enter selects and navigates', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');

    // Focus first treeitem and press Enter
    const firstItem = page.locator('[role="treeitem"]').first();
    await firstItem.focus();
    await firstItem.press('Enter');

    // Should navigate to /graph
    await expect(page).toHaveURL(/\/graph/);
  });

  test('hides file tree when sidebar is collapsed', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');

    // Collapse the sidebar
    await page.getByRole('button', { name: /collapse sidebar/i }).click();

    await expect(page.getByRole('tree', { name: 'Project file tree' })).not.toBeVisible();
  });

  test('clearing file filter on graph view removes badge', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');

    // Navigate via file click
    await page.getByTestId('tree-node-pom.xml').click();
    await expect(page).toHaveURL(/\/graph/);

    const badge = page.getByTestId('file-filter-badge');
    await expect(badge).toBeVisible();

    // Click X on the badge
    await page.getByRole('button', { name: 'Clear file filter' }).click();
    await expect(badge).not.toBeVisible();
  });

  test('accessibility: tree has correct ARIA roles', async ({ page }) => {
    await page.goto('/');
    await page.waitForSelector('[aria-label="Project file tree"]');

    await expect(page.getByRole('tree', { name: 'Project file tree' })).toBeVisible();
    const items = page.locator('[role="treeitem"]');
    await expect(items).not.toHaveCount(0);
  });
});
