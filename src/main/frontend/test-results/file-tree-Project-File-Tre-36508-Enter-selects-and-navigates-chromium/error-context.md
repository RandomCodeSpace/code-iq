# Instructions

- Following Playwright test failed.
- Explain why, be concise, respect Playwright best practices.
- Provide a snippet of code with the fix, if possible.

# Test info

- Name: file-tree.spec.ts >> Project File Tree >> keyboard navigation: Enter selects and navigates
- Location: tests/e2e/file-tree.spec.ts:213:3

# Error details

```
Error: page.goto: net::ERR_CONNECTION_REFUSED at http://localhost:8080/
Call log:
  - navigating to "http://localhost:8080/", waiting until "load"

```

# Test source

```ts
  114 |     await page.waitForSelector('[aria-label="Project file tree"]');
  115 |     // src directory should be visible (root auto-expanded)
  116 |     await expect(page.getByText('src')).toBeVisible();
  117 |     // pom.xml should be visible (top-level file)
  118 |     await expect(page.getByText('pom.xml')).toBeVisible();
  119 |   });
  120 | 
  121 |   test('expands directory on click', async ({ page }) => {
  122 |     await page.goto('/');
  123 |     await page.waitForSelector('[aria-label="Project file tree"]');
  124 | 
  125 |     // 'main' subdirectory is inside 'src' which is inside root
  126 |     // Click 'src' to expand it
  127 |     await page.getByText('src').click();
  128 |     await expect(page.getByText('main')).toBeVisible();
  129 |     await expect(page.getByText('components')).toBeVisible();
  130 |   });
  131 | 
  132 |   test('collapses expanded directory on second click', async ({ page }) => {
  133 |     await page.goto('/');
  134 |     await page.waitForSelector('[aria-label="Project file tree"]');
  135 | 
  136 |     // Click src to expand
  137 |     await page.getByText('src').click();
  138 |     await expect(page.getByText('main')).toBeVisible();
  139 | 
  140 |     // Click src again to collapse
  141 |     await page.getByText('src').click();
  142 |     await expect(page.getByText('main')).not.toBeVisible();
  143 |   });
  144 | 
  145 |   test('filters tree with search input', async ({ page }) => {
  146 |     await page.goto('/');
  147 |     await page.waitForSelector('[aria-label="Project file tree"]');
  148 | 
  149 |     // Expand src to make files visible
  150 |     await page.getByText('src').click();
  151 |     await page.getByText('main').click();
  152 | 
  153 |     const searchInput = page.getByPlaceholder('Filter files…');
  154 |     await searchInput.fill('App');
  155 | 
  156 |     // Only App.tsx should be visible, not index.ts
  157 |     await expect(page.getByText('App.tsx')).toBeVisible();
  158 |     await expect(page.getByText('index.ts')).not.toBeVisible();
  159 |   });
  160 | 
  161 |   test('clears search with X button', async ({ page }) => {
  162 |     await page.goto('/');
  163 |     await page.waitForSelector('[aria-label="Project file tree"]');
  164 | 
  165 |     const searchInput = page.getByPlaceholder('Filter files…');
  166 |     await searchInput.fill('App');
  167 | 
  168 |     await page.getByRole('button', { name: 'Clear filter' }).click();
  169 |     await expect(searchInput).toHaveValue('');
  170 |   });
  171 | 
  172 |   test('shows "no match" message for unmatched query', async ({ page }) => {
  173 |     await page.goto('/');
  174 |     await page.waitForSelector('[aria-label="Project file tree"]');
  175 | 
  176 |     await page.getByPlaceholder('Filter files…').fill('xyznotfound');
  177 |     await expect(page.getByText(/No files match/)).toBeVisible();
  178 |   });
  179 | 
  180 |   test('shows node count badges', async ({ page }) => {
  181 |     await page.goto('/');
  182 |     await page.waitForSelector('[aria-label="Project file tree"]');
  183 | 
  184 |     // pom.xml has nodeCount: 2, should show badge
  185 |     const pomRow = page.getByTestId('tree-node-pom.xml');
  186 |     await expect(pomRow).toContainText('2');
  187 |   });
  188 | 
  189 |   test('navigates to graph view on file click', async ({ page }) => {
  190 |     await page.goto('/');
  191 |     await page.waitForSelector('[aria-label="Project file tree"]');
  192 | 
  193 |     // Click pom.xml (visible at root level)
  194 |     await page.getByTestId('tree-node-pom.xml').click();
  195 | 
  196 |     await expect(page).toHaveURL(/\/graph/);
  197 |     await expect(page.getByTestId('file-filter-badge')).toBeVisible();
  198 |     await expect(page.getByTestId('file-filter-badge')).toContainText('pom.xml');
  199 |   });
  200 | 
  201 |   test('keyboard navigation: ArrowDown moves focus', async ({ page }) => {
  202 |     await page.goto('/');
  203 |     await page.waitForSelector('[aria-label="Project file tree"]');
  204 | 
  205 |     // Focus the tree
  206 |     const tree = page.getByRole('tree', { name: 'Project file tree' });
  207 |     await tree.press('ArrowDown');
  208 |     // Check that a treeitem receives focus
  209 |     const focused = page.locator('[role="treeitem"]:focus');
  210 |     await expect(focused).toHaveCount(1);
  211 |   });
  212 | 
  213 |   test('keyboard navigation: Enter selects and navigates', async ({ page }) => {
> 214 |     await page.goto('/');
      |                ^ Error: page.goto: net::ERR_CONNECTION_REFUSED at http://localhost:8080/
  215 |     await page.waitForSelector('[aria-label="Project file tree"]');
  216 | 
  217 |     // Focus first treeitem and press Enter
  218 |     const firstItem = page.locator('[role="treeitem"]').first();
  219 |     await firstItem.focus();
  220 |     await firstItem.press('Enter');
  221 | 
  222 |     // Should navigate to /graph
  223 |     await expect(page).toHaveURL(/\/graph/);
  224 |   });
  225 | 
  226 |   test('hides file tree when sidebar is collapsed', async ({ page }) => {
  227 |     await page.goto('/');
  228 |     await page.waitForSelector('[aria-label="Project file tree"]');
  229 | 
  230 |     // Collapse the sidebar
  231 |     await page.getByRole('button', { name: /collapse sidebar/i }).click();
  232 | 
  233 |     await expect(page.getByRole('tree', { name: 'Project file tree' })).not.toBeVisible();
  234 |   });
  235 | 
  236 |   test('clearing file filter on graph view removes badge', async ({ page }) => {
  237 |     await page.goto('/');
  238 |     await page.waitForSelector('[aria-label="Project file tree"]');
  239 | 
  240 |     // Navigate via file click
  241 |     await page.getByTestId('tree-node-pom.xml').click();
  242 |     await expect(page).toHaveURL(/\/graph/);
  243 | 
  244 |     const badge = page.getByTestId('file-filter-badge');
  245 |     await expect(badge).toBeVisible();
  246 | 
  247 |     // Click X on the badge
  248 |     await page.getByRole('button', { name: 'Clear file filter' }).click();
  249 |     await expect(badge).not.toBeVisible();
  250 |   });
  251 | 
  252 |   test('accessibility: tree has correct ARIA roles', async ({ page }) => {
  253 |     await page.goto('/');
  254 |     await page.waitForSelector('[aria-label="Project file tree"]');
  255 | 
  256 |     await expect(page.getByRole('tree', { name: 'Project file tree' })).toBeVisible();
  257 |     const items = page.locator('[role="treeitem"]');
  258 |     await expect(items).not.toHaveCount(0);
  259 |   });
  260 | });
  261 | 
```