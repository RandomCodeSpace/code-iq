# OSSCodeIQ — Project Instructions

## What This Project Is

**OSSCodeIQ** (`osscodeiq` on PyPI) — a CLI tool + server that scans codebases to build a deterministic code knowledge graph. No AI, no external APIs — pure pattern matching. 97 detectors, 35 languages, 3 storage backends (NetworkX, SQLite, KuzuDB), REST API + MCP server, interactive flow diagrams.

- **PyPI package:** `osscodeiq`
- **CLI command:** `osscodeiq`
- **Python package:** `osscodeiq` (under `src/osscodeiq/`)
- **GitHub repo:** `RandomCodeSpace/code-iq` (repo name differs from package name)
- **Cache directory on disk:** `.code-intelligence` (legacy name, kept for backward compatibility)

## Architecture

```
FileDiscovery → Parsers → Detectors → GraphBuilder (buffered) → Linkers → LayerClassifier → GraphStore (backend)
                                                                                                    ↓
                                                                              CodeIQService (shared facade)
                                                                              ↙                    ↘
                                                                    FastAPI REST (/api)    FastMCP MCP (/mcp)
```

- **Detectors** follow the `Detector` Protocol in `detectors/base.py` — implement `name`, `supported_languages`, `detect(ctx) -> DetectorResult`
- **Backends** follow the `GraphBackend` Protocol in `graph/backend.py` — implement 16 methods. `CypherBackend` is optional for Cypher-capable backends.
- **GraphStore** is a facade delegating to a backend — never access backends directly
- **GraphBuilder** buffers all nodes and edges, flushes nodes first then edges (ensures cross-backend parity)
- **Linkers** run after all detectors, produce cross-file relationship edges
- **LayerClassifier** runs after linkers, sets `layer` property on every node
- **CodeIQService** wraps GraphStore + FlowEngine + GraphQuery + Analyzer — shared by REST and MCP
- **Server** is a single FastAPI app: `/api` (REST), `/mcp` (MCP via fastmcp streamable HTTP), `/` (welcome UI), `/docs` (OpenAPI)

## Critical Rules

### Determinism is Non-Negotiable
- Same input MUST produce same output, every time, on every backend
- No set iteration without `sorted()` first
- No dependency on thread completion order (builder uses indexed result slots)
- All detectors must be stateless pure functions — no class-level mutable state

### Cross-Backend Data Parity
- All 3 backends (NetworkX, SQLite, KuzuDB) must produce identical node and edge counts
- Edges are only added if both source and target nodes exist
- Test parity after any change to builder, store, or backends

### Windows Compatibility
- Always use `encoding="utf-8"` when reading/writing files (Windows defaults to cp1252)
- This applies to templates, vendor JS, HTML output, and any file I/O in the server

### pyproject.toml is the Single Source of Truth
- All dependencies, scripts, metadata, and package config live in `pyproject.toml`
- After ANY change to pyproject.toml, run `uv lock` and commit both files together
- Version in pyproject.toml is `0.0.0` (placeholder) — publish/beta workflows patch it at build time
- Server deps (fastapi, uvicorn, fastmcp) are core dependencies, not optional
- Only `dev` (pytest) and `kuzu` remain as optional deps

### GitHub References
- Repo URL is `RandomCodeSpace/code-iq` — do NOT change this even though package is `osscodeiq`
- SonarCloud project key: `RandomCodeSpace_code-iq`
- Badge URLs, workflow URLs, and clone URLs all use `code-iq`

## Code Conventions

- Python 3.11+, `from __future__ import annotations`
- Pydantic for data models, typer for CLI, rich for output
- FastAPI for REST API, fastmcp for MCP server (streamable HTTP, NOT SSE)
- Regex-based detection (no tree-sitter dependency for new detectors unless needed)
- `NodeKind` and `EdgeKind` enums in `models/graph.py` — add new values there
- ID format: `"{prefix}:{filepath}:{type}:{identifier}"` for cross-file uniqueness
- Properties dict for detector-specific metadata (`auth_type`, `framework`, `roles`, etc.)
- `layer` property on every node: `frontend | backend | infra | shared | unknown`
- Suppress websockets deprecation warnings in serve command (upstream uvicorn issue)

## CLI Commands

| Command | Purpose |
|---------|---------|
| `osscodeiq analyze [path]` | Scan codebase, build graph |
| `osscodeiq graph [path]` | Export graph (json, yaml, mermaid, dot) |
| `osscodeiq query [path]` | Semantic graph queries |
| `osscodeiq find [what] [path]` | Preset queries (endpoints, guards, entities, etc.) |
| `osscodeiq cypher [query]` | Raw Cypher (KuzuDB only) |
| `osscodeiq flow [path]` | Architecture flow diagrams (mermaid, json, html) |
| `osscodeiq serve [path]` | Start unified server (API + MCP) |
| `osscodeiq bundle [path]` | Create distributable package |
| `osscodeiq cache [action]` | Manage analysis cache |
| `osscodeiq plugins [action]` | List/inspect detectors |
| `osscodeiq version` | Show version info |

## Server Architecture

### Endpoints
- `GET /` — Welcome page (self-contained HTML, fetches `/api/stats`)
- `GET /api/stats` — Graph statistics
- `GET /api/nodes`, `GET /api/edges` — Paginated queries with `?kind=&limit=&offset=`
- `GET /api/nodes/{id}/neighbors` — Neighbor traversal
- `GET /api/ego/{id}` — Ego subgraph
- `GET /api/query/cycles`, `/shortest-path`, `/consumers/{id}`, `/producers/{id}`, `/callers/{id}`, `/dependencies/{id}`, `/dependents/{id}`
- `GET /api/flow/{view}` — Flow diagrams (overview, ci, deploy, runtime, auth)
- `POST /api/analyze` — Trigger analysis
- `POST /api/cypher` — Raw Cypher (400 if not KuzuDB)
- `GET /api/triage/component`, `/impact/{id}`, `/endpoints` — Agentic triage tools
- `GET /api/search?q=` — Free-text graph search
- `GET /api/file?path=` — Serve source files (path traversal protected)
- `POST /mcp` — MCP endpoint (20 tools via streamable HTTP)

### MCP Tools (20)
15 core tools (get_stats, query_nodes, query_edges, get_node_neighbors, get_ego_graph, find_cycles, find_shortest_path, find_consumers, find_producers, find_callers, find_dependencies, find_dependents, generate_flow, analyze_codebase, run_cypher) + 5 agentic triage tools (find_component_by_file, trace_impact, find_related_endpoints, search_graph, read_file).

### Key Server Files
| File | Purpose |
|------|---------|
| `server/app.py` | FastAPI app assembly, mounts /api, /mcp, / |
| `server/service.py` | CodeIQService — shared facade over GraphStore + FlowEngine + GraphQuery |
| `server/routes.py` | REST API endpoints (uses Annotated type hints) |
| `server/mcp_server.py` | FastMCP tool definitions |
| `server/middleware.py` | Auth middleware stub (no-op, ready for future auth) |
| `server/templates/welcome.html` | Self-contained welcome page |

## Testing

- `pytest tests/ -x -q` — must always pass (currently 2,074 tests, 86% coverage)
- Every detector needs: positive match test, negative match test, determinism test
- Server tests use FastAPI TestClient
- MCP tools tested by calling functions directly after `set_service()`
- All detectors use shared `detectors/utils.py` — decode_text, find_line_number, etc.
- KuzuDB tests require `kuzu` package (installed in CI via `pip install -e ".[dev,kuzu]"`)

## Key Files

| File | Purpose |
|------|---------|
| `detectors/base.py` | Detector protocol |
| `graph/backend.py` | GraphBackend + CypherBackend protocols |
| `graph/store.py` | GraphStore facade |
| `graph/builder.py` | GraphBuilder with buffered flush + linkers |
| `graph/backends/networkx.py` | Default in-memory backend |
| `graph/backends/kuzu.py` | KuzuDB embedded graph DB with Cypher |
| `graph/backends/sqlite_backend.py` | SQLite file-based backend |
| `classifiers/layer_classifier.py` | Deterministic layer classification |
| `models/graph.py` | NodeKind (31 types), EdgeKind (26 types), GraphNode, GraphEdge |
| `config.py` | Config with GraphConfig for backend selection |
| `analyzer.py` | Pipeline orchestrator |
| `cli.py` | CLI commands — constants `_GRAPH_DIR_NAME`, `_KUZU_DB_NAME`, `_SQLITE_DB_NAME` |
| `flow/engine.py` | FlowEngine — generate/render flow diagrams |
| `flow/renderer.py` | Mermaid, JSON, HTML renderers (vendor JS inlined for offline use) |
| `flow/views.py` | 5 view builders (overview, ci, deploy, runtime, auth) |
| `flow/vendor/` | Bundled Cytoscape.js + Dagre.js (no CDN — works behind firewalls) |

## Adding a New Detector

1. Create file in `detectors/<category>/my_detector.py`
2. Implement `Detector` protocol (name, supported_languages, detect method)
3. **No registry changes needed** — auto-discovered by `pkgutil.walk_packages()`
4. Create test in `tests/detectors/<category>/test_my_detector.py`
5. Include a determinism test (run twice, assert identical output)
6. Run `pytest tests/ -x -q` — all tests must pass

## CI/CD Workflows

| Workflow | File | Purpose |
|----------|------|---------|
| CI | `ci.yml` | Run tests on Python 3.11-3.12, installs `[dev,kuzu]` |
| Beta | `beta.yml` | Auto-publish beta on push to src/tests. Version: latest stable tag + incremental counter (PEP 440: `v0.1.0b0`) |
| Publish | `publish.yml` | Manual trigger. Patches version from input, builds, tests on 11 OS combos + 9 containers, publishes to PyPI, creates GitHub release |
| SonarCloud | `sonarcloud.yml` | Code quality + coverage analysis |
| SBOM | `sbom.yml` | Dependency audit |

### Beta Versioning
- Derives base version from latest stable git tag (e.g. `v0.1.0` → `0.1.0`)
- Increments beta number from existing beta tags (not commit count)
- Tags: PEP 440 format (`v0.1.0b0`, `v0.1.0b1`, ...)
- Falls back to pyproject.toml version if no stable tags exist

### PyPI Publishing
- Trusted publisher configured (environment: `pypi`)
- Version patched from workflow_dispatch input (pyproject.toml stays at `0.0.0`)
- Creates GitHub release with auto-generated changelog after successful publish

## SonarCloud

- Project key: `RandomCodeSpace_code-iq`
- Config: `sonar-project.properties` — sources at `src/osscodeiq`
- Coverage report: `coverage.xml` generated by pytest-cov
- Keep 0 bugs, 0 vulnerabilities. Cognitive complexity issues are tracked but not blocking.

## Gotchas & Lessons Learned

- **Package name ≠ repo name**: Package is `osscodeiq`, repo is `code-iq`. Never change GitHub URLs.
- **pyproject.toml section ordering matters**: `[project.urls]` must come AFTER `dependencies = [...]`, not before. TOML will silently parse dependencies as a URL key otherwise.
- **Windows encoding**: All file reads/writes must specify `encoding="utf-8"`. Minified JS vendor files contain bytes invalid in cp1252.
- **FastAPI path params with colons**: Node IDs contain colons (e.g. `gha:workflow:build`). Use `{node_id:path}` in route definitions. Route ordering matters — `/nodes/{id}/neighbors` must be registered BEFORE `/nodes/{id}`.
- **MCP transport**: Use streamable HTTP (`mcp.http_app(transport="streamable-http")`), NOT SSE.
- **Loop bounds from user input**: Cap `radius` and `depth` params (max 10) to prevent DoS. SonarCloud flags this as a vulnerability.
- **Vendor JS for offline use**: Cytoscape.js and Dagre.js are bundled in `flow/vendor/` and inlined into HTML at render time. No CDN dependencies.
- **uv.lock**: Always regenerate with `uv lock` after pyproject.toml changes.

## Updating This File

After significant changes (new detectors, new backends, architectural decisions, conventions learned), update this CLAUDE.md to reflect the current state. Keep it concise and actionable.
