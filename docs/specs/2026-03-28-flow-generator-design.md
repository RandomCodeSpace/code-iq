# Flow Generator — Design Spec

**Date:** 2026-03-28
**Status:** Approved

## Problem

The code-intelligence graph has 2M+ nodes and 3M+ edges, but there's no way to see the "big picture" — how code gets built, deployed, and runs. A developer joining a project needs a single command that shows: here's the CI pipeline, here's the deployment topology, here's how services talk to each other, here's the auth layer.

## Solution

A `FlowEngine` core library that collapses the full graph into clean, small diagrams (5-30 nodes per view) with drill-down support. Output formats: Mermaid (text), JSON (API-ready), and a self-contained interactive HTML file with click-to-drill navigation.

## Non-Goals

- No running server required for the UI (static HTML only)
- No single comprehensive diagram (too complex, nobody benefits)
- No real-time dynamic filtering in the UI (pre-computed views only)
- No new NodeKind or EdgeKind values (use existing types with properties)

## Output Consistency Requirement

**All consumers (CLI, HTTP API, MCP tool, HTML UI) MUST receive identical data from the same FlowEngine methods.** The rendering format changes, the data never does.

- `FlowEngine.generate(view)` returns a `FlowDiagram` — this is the single source of truth
- `FlowDiagram` is a plain dataclass — serializable to any format
- Renderers are pure functions: `FlowDiagram → str` (Mermaid, JSON, HTML)
- The JSON renderer output IS the API response schema — same fields, same structure, same values
- MCP tool returns the same JSON, CLI prints the same Mermaid, HTML embeds the same data
- No consumer-specific logic in the engine or views — all differentiation happens at the render layer only

```
FlowEngine.generate("ci") → FlowDiagram (identical object)
    │
    ├─ render(diagram, "mermaid") → str   (CLI prints this)
    ├─ render(diagram, "json")   → str   (API returns this, MCP returns this)
    └─ render_interactive()      → str   (HTML embeds all views as JSON, renders as Mermaid client-side)
```

If a Mermaid diagram shows 12 endpoints in the runtime view, the JSON must show 12, the API must return 12, the MCP tool must return 12. Zero divergence.

## Architecture

```
┌─────────┐  ┌──────────┐  ┌──────────┐  ┌─────────────────┐
│   CLI   │  │ HTTP API  │  │ MCP Tool │  │  Web UI (HTML)  │
│         │  │ (future)  │  │ (future) │  │  Static file    │
└────┬────┘  └────┬──────┘  └────┬─────┘  └────┬────────────┘
     │            │               │              │
     │   generate(view):          │    render_interactive():
     │   render(diagram, fmt)     │    embeds all views as JSON
     │            │               │    renders via Mermaid.js
     ▼            ▼               ▼              ▲
┌────────────────────────────────────────────────┤
│           FlowEngine (core library)            │
│                                                │
│  generate(store, view) → FlowDiagram           │
│  generate_all(store) → dict[str, FlowDiagram]  │
│  render(diagram, format) → str                 │
│  render_interactive() → str (self-contained)───┘
│                                                │
└───────┬──────────────────────┬─────────────────┘
        │                      │
   ┌────▼────────┐      ┌─────▼──────┐
   │   Views     │      │  Renderers │
   │ (5 views,   │      │ (Mermaid,  │
   │  filter +   │      │  JSON,     │
   │  rollup)    │      │  HTML)     │
   └─────────────┘      └────────────┘
```

**All 4 consumers call the same FlowEngine methods:**
- **CLI**: `engine.generate(view)` → `engine.render(diagram, "mermaid")` → prints to stdout
- **HTTP API** (future): `engine.generate(view)` → `engine.render(diagram, "json")` → returns JSON response
- **MCP Tool** (future): `engine.generate(view)` → `engine.render(diagram, "json")` → returns to agent
- **Web UI**: `engine.render_interactive()` → generates all views, bakes into static HTML with Mermaid.js client-side rendering. The HTML file is a build artifact — no server needed, open directly in browser.

FlowEngine is a standalone class — no CLI, no HTTP, no transport dependency.

## File Organization

```
src/code_intelligence/flow/
    __init__.py
    engine.py              # FlowEngine class
    views.py               # 5 view implementations
    models.py              # FlowDiagram, FlowNode, FlowEdge, FlowSubgraph
    renderer.py            # Mermaid + JSON + HTML rendering
    templates/
        interactive.html   # Self-contained drill-down UI template (~300 lines)
```

---

## 1. FlowDiagram Model (`flow/models.py`)

```python
@dataclass
class FlowNode:
    id: str
    label: str
    kind: str              # display category: "trigger", "job", "service", "endpoint", "database", "guard", etc.
    properties: dict       # count, stage, auth_type, image, etc.
    style: str = "default" # "default", "success", "warning", "danger" — for visual emphasis

@dataclass
class FlowSubgraph:
    id: str
    label: str
    nodes: list[FlowNode]
    drill_down_view: str | None  # "ci", "deploy", "runtime", "auth" — clickable in HTML

@dataclass
class FlowEdge:
    source: str
    target: str
    label: str | None = None
    style: str = "solid"   # "solid", "dotted", "thick"

@dataclass
class FlowDiagram:
    title: str
    view: str              # "overview", "ci", "deploy", "runtime", "auth"
    direction: str = "LR"  # Mermaid direction: LR, TD, etc.
    subgraphs: list[FlowSubgraph] = field(default_factory=list)
    loose_nodes: list[FlowNode] = field(default_factory=list)  # nodes not in subgraphs
    edges: list[FlowEdge] = field(default_factory=list)
    stats: dict = field(default_factory=dict)  # total_nodes, total_edges, etc.
```

---

## 2. FlowEngine (`flow/engine.py`)

```python
class FlowEngine:
    def __init__(self, store: GraphStore) -> None:
        self._store = store

    def generate(self, view: str = "overview") -> FlowDiagram:
        """Generate a single flow view diagram."""

    def generate_all(self) -> dict[str, FlowDiagram]:
        """Generate all 5 views. Used for HTML interactive output."""

    def render(self, diagram: FlowDiagram, format: str = "mermaid") -> str:
        """Render a diagram to string: mermaid, json, or dot."""

    def render_interactive(self) -> str:
        """Generate all views and bake into self-contained HTML."""
```

---

## 3. Views (`flow/views.py`)

Each view function takes a `GraphStore` and returns a `FlowDiagram`. All views:
- Filter the graph to relevant node/edge kinds
- Collapse/group nodes into a small number of display nodes
- Count collapsed nodes (e.g., "API Endpoints x42")
- Produce max ~30 FlowNodes

### 3a. Overview View (default)

Produces 4 subgraphs with 5-15 total nodes:

**CI/CD subgraph:**
- Scan for nodes from GitHub Actions / GitLab CI detectors (MODULE nodes with workflow/pipeline properties, METHOD nodes that are CI jobs)
- Collapse into: Trigger → Build → Test → Deploy (or whatever stages exist)
- If no CI detected: omit subgraph

**Infrastructure subgraph:**
- Scan for INFRA_RESOURCE nodes (K8s, Docker Compose, Terraform, Bicep)
- Group by type: "K8s Deployments x3", "Services x5", "ConfigMaps x2"
- Show CONNECTS_TO and DEPENDS_ON edges between groups

**Application subgraph:**
- Count endpoints, entities, services (classes with METHOD children)
- Collapse into: "API Endpoints x42" → "Services x15" → "Database Entities x8"
- Show CALLS/QUERIES edges between groups

**Security subgraph:**
- Count GUARD and MIDDLEWARE nodes
- Show which endpoint groups they protect
- If no guards: show "No auth detected" warning node

### 3b. CI View (`--view ci`)

- Find all GitHub Actions workflows and GitLab CI pipelines
- Show every job as a node with stage/runner properties
- Show DEPENDS_ON edges (job dependencies, `needs:`)
- Show CONTAINS edges (workflow → jobs)
- Group by stage if available
- Show trigger events as entry nodes

### 3c. Deploy View (`--view deploy`)

- Find all INFRA_RESOURCE nodes (K8s, Docker, Terraform, Bicep)
- Show Dockerfile build stages (FROM → build → runtime)
- Show K8s topology (Ingress → Service → Deployment → ConfigMap/Secret)
- Show Docker Compose services with depends_on/links
- Show Helm chart structure if detected
- Group by namespace or compose project

### 3d. Runtime View (`--view runtime`)

- Use ArchitectView rollup to get module-level graph
- Show modules as nodes with endpoint/entity/method counts
- Show DEPENDS_ON, CALLS, PRODUCES/CONSUMES edges between modules
- Highlight database connections and messaging
- Group by layer (frontend, backend, infra) using the layer classifier property

### 3e. Auth View (`--view auth`)

- Find all GUARD and MIDDLEWARE nodes
- Find all ENDPOINT nodes
- Show PROTECTS edges from guards to endpoints
- Highlight unprotected endpoints (no incoming PROTECTS edge) with "danger" style
- Group guards by auth_type (spring_security, django, jwt, etc.)
- Show auth coverage stats: "42 of 50 endpoints protected"

---

## 4. Renderers (`flow/renderer.py`)

### Mermaid Renderer

Converts `FlowDiagram` → Mermaid flowchart syntax:
- Subgraphs → `subgraph` blocks
- Node styles based on `kind` and `style`
- Edge styles: solid (→), dotted (-.->), thick (==>)
- Click handlers for HTML mode: `click nodeId callback`

### JSON Renderer

Converts `FlowDiagram` → JSON via dataclass serialization. Used by future HTTP API and MCP tools.

### HTML Renderer

Reads `templates/interactive.html`, injects all 5 view Mermaid strings as a JSON object, outputs a single self-contained HTML file.

The template:
- Loads Mermaid.js from CDN (with fallback comment for offline)
- Renders overview on page load
- Click on a subgraph → swaps to that view's Mermaid diagram
- Breadcrumb nav: `Overview > CI Pipeline`
- Stats bar showing total nodes, edges, languages, detectors
- Dark/light theme toggle
- ~300 lines total, no build step, no npm

---

## 5. New Detector: GitLab CI (`detectors/config/gitlab_ci.py`)

- Language: `yaml`
- Trigger: file path ends with `.gitlab-ci.yml` or contains `/ci/` with `.yml`
- Detects:
  - `stages:` list → ordered pipeline stages as CONFIG_KEY nodes
  - Each job (top-level key that's not a keyword) → METHOD node with properties: stage, image, script summary, environment, artifacts
  - `needs:` → DEPENDS_ON edges between jobs
  - `extends:` → EXTENDS edges to template jobs
  - `include:` → IMPORTS edges to included CI files
  - `rules:`/`only:`/`except:` → trigger conditions in properties
  - `image:` → Docker image as property
  - Tool usage in `script:` → extract docker/helm/kubectl/terraform/maven/npm calls as properties
- Produces: MODULE for the pipeline, METHOD for jobs, CONFIG_KEY for stages/triggers
- ID format: `gitlab:{filepath}:pipeline`, `gitlab:{filepath}:job:{name}`, `gitlab:{filepath}:stage:{name}`

---

## 6. Enhanced Dockerfile Detector (modify existing)

Add to `detectors/iac/dockerfile.py`:
- Detect multi-stage builds: `FROM image AS stagename`
- Create INFRA_RESOURCE node per stage with `build_stage` property
- `COPY --from=stagename` → DEPENDS_ON edge between stages
- `ARG` → CONFIG_DEFINITION node
- Track stage order for flow visualization

---

## 7. CLI Integration

Add to `cli.py`:

```python
@app.command()
def flow(
    path: Path = Path("."),
    view: str = "overview",        # overview, ci, deploy, runtime, auth
    format: str = "mermaid",       # mermaid, json, html
    backend: str = "networkx",
    output: Path | None = None,
    config: Path | None = None,
) -> None:
    """Generate architecture flow diagrams."""
```

Logic:
1. Load graph from backend (or analyze if no graph exists)
2. Create `FlowEngine(store)`
3. If format == "html": `engine.render_interactive()` → write to output file
4. Else: `engine.generate(view)` → `engine.render(diagram, format)` → print or write

---

## 8. Bundle Integration

Update `bundle` command to include `flow.html` in the zip:
```python
# In bundle command, after graph files:
flow_html = FlowEngine(result.graph).render_interactive()
zf.writestr("flow.html", flow_html)
```

---

## 9. Testing

- Unit test each view with fixture graphs containing CI, K8s, endpoint, guard nodes
- Test that overview produces 4 subgraphs with <= 15 nodes
- Test Mermaid output is valid syntax (contains `graph`, `subgraph`, `-->`)
- Test JSON output is valid JSON with expected keys
- Test HTML output contains all 5 view data blocks and Mermaid script tag
- Test GitLab CI detector with a realistic `.gitlab-ci.yml` fixture
- Determinism: generate twice, assert identical output
- Benchmark: flow generation on contoso-real-estate < 100ms

---

## 10. Determinism

- All views sort nodes/edges by ID before rendering
- Subgraph ordering is fixed (CI, Infrastructure, Application, Security for overview)
- No set iteration without sorting
- Same graph → same FlowDiagram → same Mermaid/HTML output, always
