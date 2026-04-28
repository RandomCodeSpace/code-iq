# UI

App-mode (not library-mode): codeiq ships a single React SPA bundled inside the JAR and served by Spring Boot's static-resource handler at `http://localhost:8080/` when running `codeiq serve`.

## Stack

- **Framework:** React 18.3 (`src/main/frontend/package.json`)
- **Build tool:** Vite 6.4 + TypeScript 5.7 (`src/main/frontend/vite.config.ts`, `tsconfig.json`)
- **UI kit:** Ant Design 5.24 + `@ant-design/icons` 5.6
- **Charts:** ECharts 5.6 via `echarts-for-react` 3.0
- **Routing:** `react-router-dom` 7
- **Styling:** AntD's built-in theme system (no Tailwind, no CSS Modules); `context/ThemeContext.tsx` toggles light/dark via AntD's `ConfigProvider` token system.
- **State management:** local component state + a tiny `useApi` hook (`hooks/useApi.ts`); no Redux / Zustand / React Query.
- **Data fetching:** raw `fetch` wrapped in `lib/api.ts` + `hooks/useApi.ts`.

## Entry & layout

- **HTML entry:** `src/main/frontend/index.html` (Vite default).
- **JS entry:** `src/main/frontend/src/main.tsx` → renders `<App />` (`src/main/frontend/src/App.tsx`).
- **Root shell:** `App.tsx` wires the AntD `ConfigProvider`, the `ThemeContext.Provider`, and `react-router-dom`'s `BrowserRouter` + `Routes`.
- **Layout:** `components/AppLayout.tsx` — sidebar + content area; light/dark toggle via `useTheme()` from `ThemeContext.tsx`.
- **Provider stack** (outer → inner): AntD `ConfigProvider` → `ThemeContext.Provider` → `BrowserRouter` → `AppLayout` → page route.

## Component organization

```
src/main/frontend/src/
├── main.tsx              — Vite entry, renders <App />
├── App.tsx               — providers + routes
├── env.d.ts              — Vite env-var types
├── components/
│   └── AppLayout.tsx     — sidebar + content layout, theme toggle
├── context/
│   └── ThemeContext.tsx  — light/dark toggle
├── hooks/
│   └── useApi.ts         — generic API-call hook (loading / error / data)
├── lib/
│   ├── api.ts            — fetch wrapper + endpoint helpers
│   └── mcp-tools.ts      — TOOLS, CATEGORIES, toolsByCategory, McpTool type
├── pages/                — one file per route
│   ├── Dashboard.tsx     — stats overview + MCP tool launcher
│   ├── CodebaseMap.tsx   — file-tree explorer
│   ├── Explorer.tsx      — node/edge browser with kind filter + search
│   └── McpConsole.tsx    — interactive MCP-tool playground
└── types/
    └── api.ts            — TypeScript types matching the REST API shapes
```

**Conventions:**
- **`@/...` import alias** resolves to `src/main/frontend/src/...` (`vite.config.ts` `resolve.alias` + `tsconfig.json` `paths`). Always use the alias — never `../../../`.
- **One component per file**, `PascalCase.tsx`.
- **Pages are at `src/pages/`**; shared/UI primitives at `src/components/`. Reusable, non-page UI primitives haven't grown enough to warrant a `ui/` sublayer yet — fold into `components/` until that becomes painful.
- **No test colocation** for the SPA — frontend tests are E2E only via Playwright. Component-level testing isn't currently practiced.

## Routes

(Inferred from page filenames; **verify in `src/main/frontend/src/App.tsx`** before relying.)

- `/` → `Dashboard`
- `/explorer` → `Explorer`
- `/codebase-map` → `CodebaseMap`
- `/mcp` → `McpConsole`

## Design system

- **Tokens:** AntD's built-in token system, customized via `ConfigProvider` in `App.tsx` and theme-keyed via `ThemeContext.tsx`. No standalone token file.
- **Primitives:** AntD components used directly (`Button`, `Layout`, `Menu`, `Table`, `Input`, etc.). No internal wrapper library.
- **Icons:** `@ant-design/icons` (`SunOutlined`, `MoonOutlined`, etc. — see `components/AppLayout.tsx`).

## Data fetching

`hooks/useApi.ts` wraps `lib/api.ts`'s `api.<endpoint>(...)` calls and exposes `{ data, loading, error, refetch }`. Page components use it like:

```ts
const { data, loading, error } = useApi<StatsResponse>(() => api.stats());
```

Endpoint helpers live in `lib/api.ts`; response types in `types/api.ts`. The MCP tools list — used by `Dashboard` and `McpConsole` — is a static client-side catalog at `lib/mcp-tools.ts` (it mirrors `mcp/McpTools.java` server-side; **must be kept in sync manually** when adding a tool).

## Forms & validation

Minimal — no `react-hook-form` / `formik`. The `McpConsole` builds parameter inputs dynamically from `lib/mcp-tools.ts` definitions; validation is "send and surface server error". This is fine for an internal dev tool.

## i18n / a11y / theming

- **i18n:** none. Strings are inline English. codeiq is a developer tool; no plan to localize.
- **a11y:** Playwright config integrates `@axe-core/playwright` (`src/main/frontend/package.json` devDep) — accessibility audits run as part of E2E. AntD's primitives carry sensible roles/labels; custom components inherit those.
- **Theming:** `ThemeContext.tsx` flips a boolean → AntD token theme (`defaultAlgorithm` vs `darkAlgorithm`). The toggle is in the layout header. No `prefers-color-scheme` auto-detection currently — feature gap if you care.

## Performance notes

- **Manual chunk splitting** in `vite.config.ts` (`build.rollupOptions.output.manualChunks`):
  - `vendor-react` — React + react-dom + react-router-dom
  - `vendor-antd` — antd + @ant-design/icons
  - `vendor-echarts` — echarts + echarts-for-react

  Keeps the AntD chunk and the ECharts chunk out of the initial paint; both are heavy.
- **`chunkSizeWarningLimit: 1200`** — Vite's default 500 KB warning was too noisy for the AntD chunk; raised deliberately.
- **`emptyOutDir: false`** — preserves manually-placed assets in `src/main/resources/static/` between builds. If you see leftover files, delete the dir manually.
- **`sourcemap: false`** — production output ships without sourcemaps (the JAR is the ship artifact; sourcemaps would balloon it).

## Dev loop

```bash
# Backend — terminal 1
java -jar target/code-iq-*-cli.jar serve /path/to/scan-target

# Frontend — terminal 2
cd src/main/frontend
npm install        # only first time
npm run dev        # Vite HMR on :5173, proxies /api and /mcp to :8080
```

The Vite dev-server proxy is defined at the bottom of `vite.config.ts`:

```ts
server: {
  proxy: {
    '/api': 'http://localhost:8080',
    '/mcp': 'http://localhost:8080',
  },
}
```

## Production build → JAR embed

`mvn package` triggers `frontend-maven-plugin` which runs `npm ci` + `npm run build`. Vite's `build.outDir: '../resources/static'` writes assets into `src/main/resources/static/`, which Spring Boot's static-resource handler serves out of the JAR at runtime when `codeiq.ui.enabled=true` (default true; toggle in `application.yml`).

To skip the frontend build during backend-only iteration: `mvn test -Dfrontend.skip=true` (the property is wired in `pom.xml`'s `<properties>` block as `<frontend.skip>false</frontend.skip>`).

## Gotchas

- **`lib/mcp-tools.ts` is hand-maintained** — when you add a new `@McpTool` in `mcp/McpTools.java`, you must mirror the entry in `lib/mcp-tools.ts` for the `McpConsole` and `Dashboard` to know about it. There is no auto-sync.
- **`emptyOutDir: false`** — stale assets in `src/main/resources/static/` won't be deleted by Vite. If you renamed a chunk or removed a page, manually delete the static dir before the next build.
- **MCP endpoint path is `/mcp`**, not `/api/mcp` — the Vite proxy reflects this. The Spring AI starter mounts MCP at the root.
- **AntD chunk size is intentional.** Don't try to "fix" the 500 KB+ AntD chunk by code-splitting per page — the AntD design tokens shouldn't be reloaded per route. The manual chunk in `vite.config.ts` is the right granularity.
