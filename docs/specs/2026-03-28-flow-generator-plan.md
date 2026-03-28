# Flow Generator Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate clean architecture flow diagrams (Mermaid, JSON, interactive HTML) from the code intelligence graph, with a high-level overview and drill-down views for CI, deploy, runtime, and auth.

**Architecture:** `FlowEngine` core library with 5 view generators, 3 renderers (Mermaid, JSON, HTML), a GitLab CI detector, enhanced Dockerfile detection, and a CLI `flow` command. All consumers (CLI, future API/MCP, HTML UI) call the same engine. Output consistency enforced via `FlowDiagram` dataclass as single source of truth.

**Tech Stack:** Python 3.11+, Mermaid.js (CDN for HTML), existing GraphStore/Detector protocols

---

## Task 1: FlowDiagram Models (`flow/models.py`)

**Files:**
- Create: `src/code_intelligence/flow/__init__.py`
- Create: `src/code_intelligence/flow/models.py`
- Create: `src/code_intelligence/flow/templates/` (empty dir)
- Test: `tests/flow/test_models.py`

- [ ] **Step 1: Create directories**

```bash
mkdir -p src/code_intelligence/flow/templates
touch src/code_intelligence/flow/__init__.py
mkdir -p tests/flow
touch tests/flow/__init__.py
```

- [ ] **Step 2: Write the models**

Create `src/code_intelligence/flow/models.py`:

```python
"""Data models for flow diagrams."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class FlowNode:
    """A node in a flow diagram (collapsed/summarized from graph nodes)."""
    id: str
    label: str
    kind: str               # "trigger", "job", "service", "endpoint", "database", "guard", etc.
    properties: dict = field(default_factory=dict)
    style: str = "default"  # "default", "success", "warning", "danger"


@dataclass
class FlowSubgraph:
    """A labeled group of nodes in a flow diagram."""
    id: str
    label: str
    nodes: list[FlowNode] = field(default_factory=list)
    drill_down_view: str | None = None  # "ci", "deploy", "runtime", "auth"


@dataclass
class FlowEdge:
    """An edge in a flow diagram."""
    source: str
    target: str
    label: str | None = None
    style: str = "solid"    # "solid", "dotted", "thick"


@dataclass
class FlowDiagram:
    """A complete flow diagram — the single source of truth for all renderers."""
    title: str
    view: str               # "overview", "ci", "deploy", "runtime", "auth"
    direction: str = "LR"
    subgraphs: list[FlowSubgraph] = field(default_factory=list)
    loose_nodes: list[FlowNode] = field(default_factory=list)
    edges: list[FlowEdge] = field(default_factory=list)
    stats: dict = field(default_factory=dict)

    def all_nodes(self) -> list[FlowNode]:
        """Return all nodes across subgraphs and loose nodes."""
        result = list(self.loose_nodes)
        for sg in self.subgraphs:
            result.extend(sg.nodes)
        return result

    def to_dict(self) -> dict:
        """Serialize to a plain dict (for JSON renderer and API responses)."""
        return {
            "title": self.title,
            "view": self.view,
            "direction": self.direction,
            "subgraphs": [
                {
                    "id": sg.id,
                    "label": sg.label,
                    "drill_down_view": sg.drill_down_view,
                    "nodes": [{"id": n.id, "label": n.label, "kind": n.kind, "properties": n.properties, "style": n.style} for n in sg.nodes],
                }
                for sg in self.subgraphs
            ],
            "loose_nodes": [{"id": n.id, "label": n.label, "kind": n.kind, "properties": n.properties, "style": n.style} for n in self.loose_nodes],
            "edges": [{"source": e.source, "target": e.target, "label": e.label, "style": e.style} for e in self.edges],
            "stats": self.stats,
        }
```

- [ ] **Step 3: Write tests**

Create `tests/flow/test_models.py`:

```python
"""Tests for flow diagram models."""

from code_intelligence.flow.models import FlowDiagram, FlowEdge, FlowNode, FlowSubgraph


def test_flow_diagram_all_nodes():
    sg = FlowSubgraph(id="sg1", label="Group", nodes=[FlowNode(id="n1", label="A", kind="job")])
    d = FlowDiagram(title="Test", view="ci", subgraphs=[sg], loose_nodes=[FlowNode(id="n2", label="B", kind="trigger")])
    assert len(d.all_nodes()) == 2


def test_flow_diagram_to_dict():
    d = FlowDiagram(title="Test", view="overview", stats={"total": 100})
    data = d.to_dict()
    assert data["title"] == "Test"
    assert data["view"] == "overview"
    assert data["stats"]["total"] == 100


def test_flow_diagram_to_dict_consistency():
    sg = FlowSubgraph(id="ci", label="CI", drill_down_view="ci", nodes=[FlowNode(id="j1", label="build", kind="job")])
    d = FlowDiagram(title="T", view="overview", subgraphs=[sg], edges=[FlowEdge(source="ci", target="deploy")])
    d1 = d.to_dict()
    d2 = d.to_dict()
    assert d1 == d2  # Deterministic serialization
```

- [ ] **Step 4: Run tests**

Run: `pytest tests/flow/test_models.py -v`
Expected: 3 tests pass

- [ ] **Step 5: Commit**

```bash
git add src/code_intelligence/flow/ tests/flow/
git commit -m "feat: add FlowDiagram models for flow generator"
```

---

## Task 2: Flow Views (`flow/views.py`)

**Files:**
- Create: `src/code_intelligence/flow/views.py`
- Test: `tests/flow/test_views.py`

- [ ] **Step 1: Implement all 5 views**

Create `src/code_intelligence/flow/views.py`. Each view function takes a `GraphStore` and returns a `FlowDiagram`. Key logic per view:

**`build_overview(store)`**: Query store for CI nodes (MODULE with github_actions/gitlab_ci name prefix), INFRA_RESOURCE nodes, ENDPOINT/ENTITY/CLASS/METHOD counts, GUARD nodes. Create 4 subgraphs (CI, Infrastructure, Application, Security) with collapsed summary nodes like "Endpoints x42". Max 15 nodes total.

**`build_ci_view(store)`**: Find all nodes from CI detectors (github_actions, gitlab_ci). Show each workflow/pipeline as a subgraph, each job as a node. Show DEPENDS_ON edges between jobs, CONTAINS from workflow to jobs. Group by stage if available.

**`build_deploy_view(store)`**: Find INFRA_RESOURCE nodes. Group K8s resources by namespace, Docker services by compose file, Terraform resources by module. Show CONNECTS_TO and DEPENDS_ON edges.

**`build_runtime_view(store)`**: Count ENDPOINT, ENTITY, CLASS, METHOD, TOPIC, QUEUE nodes per module. Create module-level summary nodes. Show inter-module CALLS, DEPENDS_ON, PRODUCES/CONSUMES edges. Group by layer property (frontend, backend, infra).

**`build_auth_view(store)`**: Find all GUARD and ENDPOINT nodes. Count endpoints protected vs unprotected. Show guards grouped by auth_type. Highlight unprotected endpoints with "danger" style. Show coverage stats.

Each view must:
- Sort all nodes by ID before returning (determinism)
- Include stats dict with relevant counts
- Never exceed ~30 FlowNodes

- [ ] **Step 2: Write tests**

Create `tests/flow/test_views.py` with fixtures containing representative nodes and tests for each view returning valid FlowDiagram with expected subgraph counts.

- [ ] **Step 3: Run tests and commit**

---

## Task 3: Flow Renderers (`flow/renderer.py`)

**Files:**
- Create: `src/code_intelligence/flow/renderer.py`
- Test: `tests/flow/test_renderer.py`

- [ ] **Step 1: Implement Mermaid renderer**

Function `render_mermaid(diagram: FlowDiagram) -> str` that:
- Outputs `graph {direction}` header
- Each subgraph as Mermaid `subgraph id["label"]` blocks
- Nodes with shape based on kind (stadium for jobs, hexagon for endpoints, cylinder for database, etc.)
- Edges with arrow style based on FlowEdge.style
- Sorts everything by ID for determinism

- [ ] **Step 2: Implement JSON renderer**

Function `render_json(diagram: FlowDiagram) -> str` that calls `diagram.to_dict()` and `json.dumps()`.

- [ ] **Step 3: Implement HTML renderer**

Function `render_html(views: dict[str, FlowDiagram], stats: dict) -> str` that:
- Reads the template from `flow/templates/interactive.html`
- Replaces `{{VIEWS_DATA}}` placeholder with JSON of all views' Mermaid strings
- Replaces `{{STATS}}` with stats JSON
- Returns self-contained HTML string

- [ ] **Step 4: Write tests, run, commit**

---

## Task 4: Interactive HTML Template (`flow/templates/interactive.html`)

**Files:**
- Create: `src/code_intelligence/flow/templates/interactive.html`

- [ ] **Step 1: Create the template**

Self-contained HTML (~300 lines) with:
- Mermaid.js loaded from CDN
- `const VIEWS = {{VIEWS_DATA}};` placeholder for injected data
- `const STATS = {{STATS}};` placeholder
- Renders overview on load
- Click handler: subgraph click → swap Mermaid source → re-render
- Breadcrumb navigation with back button
- Stats bar showing node/edge/detector/language counts
- Clean minimal CSS, dark/light theme support
- Mobile-responsive layout

- [ ] **Step 2: Commit**

---

## Task 5: FlowEngine (`flow/engine.py`)

**Files:**
- Create: `src/code_intelligence/flow/engine.py`
- Test: `tests/flow/test_engine.py`

- [ ] **Step 1: Implement FlowEngine**

```python
class FlowEngine:
    def __init__(self, store: GraphStore) -> None:
        self._store = store

    def generate(self, view: str = "overview") -> FlowDiagram:
        views_map = {
            "overview": build_overview,
            "ci": build_ci_view,
            "deploy": build_deploy_view,
            "runtime": build_runtime_view,
            "auth": build_auth_view,
        }
        builder = views_map.get(view)
        if builder is None:
            raise ValueError(f"Unknown view: {view}. Available: {', '.join(views_map)}")
        return builder(self._store)

    def generate_all(self) -> dict[str, FlowDiagram]:
        return {v: self.generate(v) for v in ["overview", "ci", "deploy", "runtime", "auth"]}

    def render(self, diagram: FlowDiagram, format: str = "mermaid") -> str:
        if format == "mermaid":
            return render_mermaid(diagram)
        elif format == "json":
            return render_json(diagram)
        else:
            raise ValueError(f"Unknown format: {format}. Available: mermaid, json")

    def render_interactive(self) -> str:
        all_views = self.generate_all()
        stats = {
            "total_nodes": self._store.node_count,
            "total_edges": self._store.edge_count,
        }
        return render_html(all_views, stats)
```

- [ ] **Step 2: Write tests for generate, render, render_interactive**

- [ ] **Step 3: Run tests and commit**

---

## Task 6: GitLab CI Detector (`detectors/config/gitlab_ci.py`)

**Files:**
- Create: `src/code_intelligence/detectors/config/gitlab_ci.py`
- Test: `tests/detectors/config/test_gitlab_ci.py`

- [ ] **Step 1: Implement GitLabCIDetector**

Language: yaml. Trigger: file path ends with `.gitlab-ci.yml`.

Detect:
- `stages:` list → CONFIG_KEY nodes per stage
- Each job (top-level key not in keywords set) → METHOD node with stage, image, script properties
- `needs:` → DEPENDS_ON edges between jobs
- `extends:` → EXTENDS edges to template
- `include:` → IMPORTS edges
- `image:` → property on job node
- Tool usage in script (docker, helm, kubectl, terraform, maven, npm) → properties

Produce: MODULE for pipeline, METHOD for jobs, CONFIG_KEY for stages/triggers, CONTAINS/DEPENDS_ON edges.

Keywords to skip: `stages`, `variables`, `default`, `workflow`, `include`, `image`, `services`, `before_script`, `after_script`, `cache`.

- [ ] **Step 2: Write tests with realistic .gitlab-ci.yml content**

- [ ] **Step 3: Run tests and commit**

---

## Task 7: Enhanced Dockerfile Detector

**Files:**
- Modify: `src/code_intelligence/detectors/iac/dockerfile.py`
- Test: `tests/detectors/iac/test_dockerfile.py` (add tests)

- [ ] **Step 1: Add multi-stage build detection**

The existing `_FROM_RE` already captures `AS stagename`. Add:
- `_COPY_FROM_RE = re.compile(r'COPY\s+--from=(\w+)', re.MULTILINE | re.IGNORECASE)`
- For each FROM with AS: add `build_stage` property to the INFRA_RESOURCE node
- For each COPY --from: create DEPENDS_ON edge from current stage to source stage
- Track stage order in properties

- [ ] **Step 2: Add tests for multi-stage builds**

- [ ] **Step 3: Run tests and commit**

---

## Task 8: CLI `flow` Command

**Files:**
- Modify: `src/code_intelligence/cli.py` (add flow command before bundle)

- [ ] **Step 1: Add flow command**

```python
@app.command()
def flow(
    path: Annotated[Path, typer.Argument(help="Path to analyzed codebase")] = Path("."),
    view: Annotated[str, typer.Option("--view", "-v", help="View: overview, ci, deploy, runtime, auth")] = "overview",
    format: Annotated[str, typer.Option("--format", "-f", help="Format: mermaid, json, html")] = "mermaid",
    backend: Annotated[str, typer.Option("--backend", "-b", help="Graph backend")] = "networkx",
    output: Annotated[Optional[Path], typer.Option("--output", "-o", help="Output file path")] = None,
    config: Annotated[Optional[Path], typer.Option("--config", "-c")] = None,
) -> None:
    """Generate architecture flow diagrams."""
    store = _load_graph_backend(path, backend, config)

    from code_intelligence.flow.engine import FlowEngine
    engine = FlowEngine(store)

    if format == "html":
        content = engine.render_interactive()
        out_path = output or Path("flow.html")
        out_path.write_text(content)
        console.print(f"Interactive flow diagram saved to [bold]{out_path}[/bold]")
    else:
        diagram = engine.generate(view)
        content = engine.render(diagram, format)
        if output:
            output.write_text(content)
            console.print(f"Flow diagram saved to [bold]{output}[/bold]")
        else:
            console.print(content)

    store.close()
```

- [ ] **Step 2: Update bundle command to include flow.html**

In the bundle command, after writing graph files to the zip, add:
```python
try:
    from code_intelligence.flow.engine import FlowEngine
    flow_html = FlowEngine(result.graph).render_interactive()
    zf.writestr("flow.html", flow_html)
except Exception:
    logger.debug("Flow HTML generation failed, skipping", exc_info=True)
```

- [ ] **Step 3: Verify CLI help**

Run: `code-intelligence flow --help`
Expected: Shows view, format, backend, output options

- [ ] **Step 4: Commit**

---

## Task 9: Integration Test & Benchmark

- [ ] **Step 1: Run full test suite**

Run: `pytest tests/ -x -q`
Expected: All tests pass (565 existing + new flow tests)

- [ ] **Step 2: Integration test on real project**

```bash
rm -rf ~/projects/testDir/contoso-real-estate/.code-intelligence/
find ~/projects/testDir/contoso-real-estate -name ".code_intelligence_cache*" -delete

# Analyze
code-intelligence analyze ~/projects/testDir/contoso-real-estate --full -j 8

# Generate each view
code-intelligence flow ~/projects/testDir/contoso-real-estate --view overview
code-intelligence flow ~/projects/testDir/contoso-real-estate --view ci
code-intelligence flow ~/projects/testDir/contoso-real-estate --view deploy
code-intelligence flow ~/projects/testDir/contoso-real-estate --view runtime
code-intelligence flow ~/projects/testDir/contoso-real-estate --view auth

# Generate JSON
code-intelligence flow ~/projects/testDir/contoso-real-estate --format json --view overview

# Generate interactive HTML
code-intelligence flow ~/projects/testDir/contoso-real-estate --format html --output contoso-flow.html

# Verify HTML exists and has content
wc -c contoso-flow.html
```

- [ ] **Step 3: Determinism check**

Generate flow twice, assert identical output.

- [ ] **Step 4: Benchmark**

Flow generation should add < 100ms to a typical analysis.

- [ ] **Step 5: Final commit and push**

```bash
git push
```
