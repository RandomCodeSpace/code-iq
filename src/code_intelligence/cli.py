"""CLI entry point for code-intelligence."""

from __future__ import annotations

from pathlib import Path
from typing import Annotated, Optional

import typer
from rich.console import Console

from code_intelligence.config import Config

app = typer.Typer(
    name="code-intelligence",
    help="Intelligent code graph discovery and analysis CLI.",
    no_args_is_help=True,
)
console = Console()


def _load_config(config: Path | None, project_path: Path | None = None) -> Config:
    return Config.load(config, project_path=project_path)


@app.command()
def analyze(
    path: Annotated[Path, typer.Argument(help="Path to the codebase to analyze")] = Path("."),
    incremental: Annotated[bool, typer.Option("--incremental/--full", help="Use incremental analysis")] = True,
    parallelism: Annotated[int, typer.Option("--parallelism", "-j", help="Number of parallel workers")] = 8,
    backend: Annotated[str, typer.Option("--backend", "-b", help="Graph backend (networkx, kuzu, sqlite)")] = "networkx",
    config: Annotated[Optional[Path], typer.Option("--config", "-c", help="Path to config file")] = None,
) -> None:
    """Analyze a codebase and build the code intelligence graph."""
    from code_intelligence.analyzer import Analyzer

    cfg = _load_config(config)
    cfg.analysis.parallelism = parallelism
    cfg.analysis.incremental = incremental
    cfg.graph.backend = backend
    if backend in ("kuzu", "sqlite"):
        graph_dir = path.resolve() / ".code-intelligence"
        if backend == "kuzu":
            cfg.graph.path = str(graph_dir / "graph.kuzu")
        elif backend == "sqlite":
            cfg.graph.path = str(graph_dir / "graph.db")

    console.print("🚀 Starting analysis…")
    analyzer = Analyzer(cfg)
    result = analyzer.run(
        path.resolve(),
        incremental=incremental,
        on_progress=console.print,
    )
    console.print()
    console.print(f"📊 [bold]Results:[/bold]  {result.graph.node_count} nodes, {result.graph.edge_count} edges")
    console.print(f"   📂 {result.total_files} total files — {result.files_cached} cached, {result.files_analyzed} analyzed")

    # Language breakdown
    if result.language_breakdown:
        console.print()
        console.print("📋 [bold]Language Breakdown:[/bold]")

        # We need the registry to check detector support
        from code_intelligence.detectors.registry import DetectorRegistry
        from code_intelligence.analyzer import _TREESITTER_LANGUAGES, _STRUCTURED_LANGUAGES

        registry = DetectorRegistry()
        registry.load_builtin_detectors()
        registry.load_plugin_detectors()

        # Sort by file count descending
        sorted_langs = sorted(result.language_breakdown.items(), key=lambda x: -x[1])

        for lang, count in sorted_langs:
            detectors = registry.detectors_for_language(lang)
            if detectors:
                det_count = len(detectors)
                status = f"🟢 {det_count} detector{'s' if det_count != 1 else ''}"
            elif lang in _TREESITTER_LANGUAGES or lang in _STRUCTURED_LANGUAGES:
                status = "🟡 parsed"
            else:
                status = "🔴 discovered only"
            console.print(f"   {status}  {lang:<16} {count:>6} files")

    # Node breakdown
    if result.node_breakdown:
        console.print()
        console.print("🏗️  [bold]Detection Summary:[/bold]")
        sorted_kinds = sorted(result.node_breakdown.items(), key=lambda x: -x[1])
        for kind, count in sorted_kinds:
            console.print(f"   {kind:<24} {count:>8,}")


@app.command()
def graph(
    path: Annotated[Path, typer.Argument(help="Path to the analyzed codebase")] = Path("."),
    format: Annotated[str, typer.Option("--format", "-f", help="Output format: json, yaml, mermaid, dot")] = "json",
    view: Annotated[str, typer.Option("--view", "-v", help="View level: developer, architect, domain")] = "developer",
    module: Annotated[Optional[list[str]], typer.Option("--module", "-m", help="Filter by module")] = None,
    node_type: Annotated[Optional[list[str]], typer.Option("--node-type", help="Filter by node type")] = None,
    edge_type: Annotated[Optional[list[str]], typer.Option("--edge-type", help="Filter by edge type")] = None,
    focus: Annotated[Optional[str], typer.Option("--focus", help="Center node for ego-graph")] = None,
    hops: Annotated[int, typer.Option("--hops", help="Radius from focus node")] = 2,
    output: Annotated[Optional[Path], typer.Option("--output", "-o", help="Output file path")] = None,
    max_nodes: Annotated[int, typer.Option("--max-nodes", help="Maximum nodes before safety guard")] = 500,
    cluster_by: Annotated[str, typer.Option("--cluster-by", help="Clustering: module, domain, node-type")] = "module",
    config: Annotated[Optional[Path], typer.Option("--config", "-c", help="Path to config file")] = None,
) -> None:
    """Export the code intelligence graph in various formats."""
    from code_intelligence.graph.query import GraphQuery
    from code_intelligence.graph.store import GraphStore
    from code_intelligence.graph.views import ArchitectView, DomainView
    from code_intelligence.models.graph import EdgeKind, NodeKind
    from code_intelligence.output.safety import check_graph_size
    from code_intelligence.output.serializers import JsonSerializer, YamlSerializer
    from code_intelligence.output.mermaid import MermaidRenderer
    from code_intelligence.output.dot import DotRenderer

    cfg = _load_config(config)
    cache_path = path.resolve() / cfg.cache.directory / cfg.cache.db_name

    if not cache_path.exists():
        console.print("❌ No analysis cache found. Run 'code-intelligence analyze' first.")
        raise typer.Exit(1)

    console.print("💾 Loading analysis cache…")
    from code_intelligence.cache.store import CacheStore
    cache = CacheStore(cache_path)
    store = cache.load_full_graph()

    # Apply view transformation
    if view == "architect":
        console.print("🔭 Applying architect view…")
        store = ArchitectView().roll_up(store)
    elif view == "domain":
        console.print("🔭 Applying domain view…")
        store = DomainView(cfg.domains).roll_up(store)

    # Apply filters via query builder
    query = GraphQuery(store)
    has_filters = any([module, node_type, edge_type, focus])
    if has_filters:
        console.print("🔍 Applying filters…")
    if module:
        query = query.filter_modules(module)
    if node_type:
        kinds = [NodeKind(t) for t in node_type]
        query = query.filter_node_kinds(kinds)
    if edge_type:
        e_kinds = [EdgeKind(t) for t in edge_type]
        query = query.filter_edge_kinds(e_kinds)
    if focus:
        query = query.focus(focus, hops)

    result_store = query.execute()

    # Safety check
    check_graph_size(result_store, max_nodes, console)

    # Render output
    console.print(f"🎨 Rendering {format} output…")
    model = result_store.to_model()
    model.metadata["view"] = view
    model.metadata["filters_applied"] = {
        "modules": module,
        "node_types": node_type,
        "edge_types": edge_type,
        "focus": focus,
        "hops": hops if focus else None,
    }

    if format == "json":
        content = JsonSerializer().serialize(model)
    elif format == "yaml":
        content = YamlSerializer().serialize(model)
    elif format == "mermaid":
        content = MermaidRenderer().render(result_store, cluster_by=cluster_by)
    elif format == "dot":
        content = DotRenderer().render(result_store, cluster_by=cluster_by)
    else:
        console.print(f"❌ Unknown format: {format}")
        raise typer.Exit(1)

    if output:
        output.write_text(content)
        console.print(f"✅ Graph written to {output}")
    else:
        console.print(content)


@app.command()
def query(
    path: Annotated[Path, typer.Argument(help="Path to the analyzed codebase")] = Path("."),
    consumers_of: Annotated[Optional[str], typer.Option("--consumers-of", help="Show consumers of topic/queue")] = None,
    producers_of: Annotated[Optional[str], typer.Option("--producers-of", help="Show producers to topic/queue")] = None,
    callers_of: Annotated[Optional[str], typer.Option("--callers-of", help="Show callers of endpoint/method")] = None,
    dependencies_of: Annotated[Optional[str], typer.Option("--dependencies-of", help="Show dependencies of module")] = None,
    dependents_of: Annotated[Optional[str], typer.Option("--dependents-of", help="Show dependents of module")] = None,
    cycles: Annotated[bool, typer.Option("--cycles", help="Detect circular dependencies")] = False,
    config: Annotated[Optional[Path], typer.Option("--config", "-c", help="Path to config file")] = None,
) -> None:
    """Query the code intelligence graph."""
    cfg = _load_config(config)
    cache_path = path.resolve() / cfg.cache.directory / cfg.cache.db_name

    if not cache_path.exists():
        console.print("❌ No analysis cache found. Run 'code-intelligence analyze' first.")
        raise typer.Exit(1)

    console.print("💾 Loading analysis cache…")
    from code_intelligence.cache.store import CacheStore
    from code_intelligence.graph.query import GraphQuery

    cache = CacheStore(cache_path)
    store = cache.load_full_graph()
    q = GraphQuery(store)

    if consumers_of:
        console.print(f"🔍 Querying consumers of '{consumers_of}'…")
        result = q.consumers_of(consumers_of).execute()
        _print_query_result(result, f"Consumers of '{consumers_of}'")
    elif producers_of:
        console.print(f"🔍 Querying producers of '{producers_of}'…")
        result = q.producers_of(producers_of).execute()
        _print_query_result(result, f"Producers of '{producers_of}'")
    elif callers_of:
        console.print(f"🔍 Querying callers of '{callers_of}'…")
        result = q.callers_of(callers_of).execute()
        _print_query_result(result, f"Callers of '{callers_of}'")
    elif dependencies_of:
        console.print(f"🔍 Querying dependencies of '{dependencies_of}'…")
        result = q.dependencies_of(dependencies_of).execute()
        _print_query_result(result, f"Dependencies of '{dependencies_of}'")
    elif dependents_of:
        console.print(f"🔍 Querying dependents of '{dependents_of}'…")
        result = q.dependents_of(dependents_of).execute()
        _print_query_result(result, f"Dependents of '{dependents_of}'")
    elif cycles:
        console.print("🔄 Detecting circular dependencies…")
        cycle_list = store.find_cycles()
        if cycle_list:
            console.print(f"⚠️  Found {len(cycle_list)} cycles:")
            for i, cycle in enumerate(cycle_list[:20], 1):
                console.print(f"  {i}. {' → '.join(cycle)}")
            if len(cycle_list) > 20:
                console.print(f"  … and {len(cycle_list) - 20} more")
        else:
            console.print("✅ No circular dependencies found!")
    else:
        console.print("⚠️  Specify a query option. Use --help for available queries.")


def _print_query_result(store: "GraphStore", title: str) -> None:  # noqa: F821
    nodes = store.all_nodes()
    console.print(f"📊 [bold]{title}[/bold] ({len(nodes)} results):")
    for node in nodes:
        loc = f" ({node.location.file_path}:{node.location.line_start})" if node.location else ""
        console.print(f"  [{node.kind.value}] {node.label}{loc}")


@app.command()
def cache(
    action: Annotated[str, typer.Argument(help="Action: stats, clear")],
    path: Annotated[Path, typer.Argument(help="Path to the codebase")] = Path("."),
    config: Annotated[Optional[Path], typer.Option("--config", "-c")] = None,
) -> None:
    """Manage the analysis cache."""
    cfg = _load_config(config)
    cache_path = path.resolve() / cfg.cache.directory / cfg.cache.db_name

    if action == "clear":
        if cache_path.exists():
            console.print("🗑️  Clearing cache…")
            cache_path.unlink()
            console.print("✅ Cache cleared!")
        else:
            console.print("⚠️  No cache found.")
    elif action == "stats":
        if not cache_path.exists():
            console.print("⚠️  No cache found. Run 'code-intelligence analyze' first.")
            return
        console.print("📊 Loading cache statistics…")
        from code_intelligence.cache.store import CacheStore
        cs = CacheStore(cache_path)
        stats = cs.get_stats()
        console.print("📊 [bold]Cache Statistics:[/bold]")
        for key, value in stats.items():
            console.print(f"   {key}: {value}")
    else:
        console.print(f"❌ Unknown action: {action}. Use 'stats' or 'clear'.")


@app.command()
def plugins(
    action: Annotated[str, typer.Argument(help="Action: list, info")] = "list",
    name: Annotated[Optional[str], typer.Argument(help="Plugin name (for info)")] = None,
) -> None:
    """Manage detector plugins."""
    from code_intelligence.detectors.registry import DetectorRegistry

    console.print("🔌 Loading detectors…")
    registry = DetectorRegistry()
    registry.load_builtin_detectors()
    registry.load_plugin_detectors()

    if action == "list":
        detectors = registry.all_detectors()
        console.print(f"📋 [bold]Registered detectors ({len(detectors)}):[/bold]")
        for det in detectors:
            langs = ", ".join(det.supported_languages)
            console.print(f"   🔹 {det.name} [{langs}]")
    elif action == "info" and name:
        det = registry.get(name)
        if det:
            console.print(f"🔹 [bold]{det.name}[/bold]")
            console.print(f"   Languages: {', '.join(det.supported_languages)}")
        else:
            console.print(f"❌ Detector '{name}' not found.")
    else:
        console.print("⚠️  Use 'list' or 'info <name>'.")


@app.command()
def bundle(
    path: Annotated[Path, typer.Argument(help="Path to analyzed codebase")] = Path("."),
    tag: Annotated[str, typer.Option("--tag", "-t", help="Version tag")] = "latest",
    backend: Annotated[str, typer.Option("--backend", "-b", help="Graph backend")] = "kuzu",
    output: Annotated[Path | None, typer.Option("--output", "-o", help="Output zip path")] = None,
    config: Annotated[Optional[Path], typer.Option("--config", "-c")] = None,
) -> None:
    """Analyze and package graph into a distributable bundle."""
    import json
    import zipfile
    from datetime import datetime, timezone

    cfg = _load_config(config)
    cfg.graph.backend = backend

    # Set default path for file-based backends
    graph_dir = path.resolve() / ".code-intelligence"
    if backend == "kuzu":
        cfg.graph.path = str(graph_dir / "graph.kuzu")
    elif backend == "sqlite":
        cfg.graph.path = str(graph_dir / "graph.db")

    # Run analysis
    from code_intelligence.analyzer import Analyzer
    analyzer = Analyzer(cfg)
    result = analyzer.run(path.resolve(), incremental=False)

    # Determine output path
    project_name = path.resolve().name
    if output is None:
        output = Path(f"{project_name}-{tag}-codegraph.zip")

    # Create bundle
    manifest = {
        "tag": tag,
        "backend": backend,
        "project": project_name,
        "created_at": datetime.now(timezone.utc).isoformat(),
        "node_count": result.graph.node_count,
        "edge_count": result.graph.edge_count,
        "files_analyzed": result.total_files,
        "code_intelligence_version": "0.1.0",
    }

    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as zf:
        # Write manifest
        zf.writestr("manifest.json", json.dumps(manifest, indent=2))

        # Bundle the graph database files
        if backend == "kuzu" and cfg.graph.path:
            graph_path = Path(cfg.graph.path)
            if graph_path.exists():
                for f in graph_path.rglob("*"):
                    if f.is_file():
                        zf.write(f, f"graph/{f.relative_to(graph_path)}")
        elif backend == "sqlite" and cfg.graph.path:
            graph_path = Path(cfg.graph.path)
            if graph_path.exists():
                zf.write(graph_path, "graph/graph.db")
        else:
            # NetworkX -- serialize to JSON
            model = result.graph.to_model()
            from code_intelligence.output.serializers import JsonSerializer
            zf.writestr("graph/graph.json", JsonSerializer().serialize(model))

        # Include interactive flow HTML
        try:
            from code_intelligence.flow.engine import FlowEngine
            flow_html = FlowEngine(result.graph).render_interactive()
            zf.writestr("flow.html", flow_html)
        except Exception:
            pass  # Flow generation is optional in bundles

    result.graph.close()

    console.print(f"Bundle created: [bold]{output}[/bold]")
    console.print(f"   Tag: {tag}")
    console.print(f"   Backend: {backend}")
    console.print(f"   Nodes: {manifest['node_count']}, Edges: {manifest['edge_count']}")
    console.print(f"   Size: {output.stat().st_size / 1024 / 1024:.1f} MB")


def _load_graph_backend(path: Path, backend: str, config: Path | None = None):
    """Load a graph backend from a previously analyzed project."""
    from code_intelligence.graph.backends import create_backend

    graph_dir = path.resolve() / ".code-intelligence"
    if backend == "kuzu":
        db_path = str(graph_dir / "graph.kuzu")
    elif backend == "sqlite":
        db_path = str(graph_dir / "graph.db")
    else:
        # NetworkX ��� load from cache
        cfg = _load_config(config)
        cache_path = path.resolve() / cfg.cache.directory / cfg.cache.db_name
        if not cache_path.exists():
            console.print("No analysis cache found. Run 'code-intelligence analyze' first.")
            raise typer.Exit(1)
        from code_intelligence.cache.store import CacheStore
        cache = CacheStore(cache_path)
        store = cache.load_full_graph()
        return store

    from pathlib import Path as P
    if not P(db_path).exists():
        console.print(f"No graph database found at {db_path}. Run 'code-intelligence analyze --backend {backend}' first.")
        raise typer.Exit(1)

    from code_intelligence.graph.store import GraphStore
    return GraphStore(backend=create_backend(backend, path=db_path))


@app.command()
def cypher(
    query_str: Annotated[str, typer.Argument(help="Cypher query to execute")],
    path: Annotated[Path, typer.Argument(help="Path to analyzed codebase")] = Path("."),
    backend: Annotated[str, typer.Option("--backend", "-b", help="Graph backend")] = "kuzu",
    limit: Annotated[int, typer.Option("--limit", "-l", help="Max rows")] = 50,
    config: Annotated[Optional[Path], typer.Option("--config", "-c")] = None,
) -> None:
    """Execute a raw Cypher query on the graph database."""
    import time as _time

    store = _load_graph_backend(path, backend, config)

    if not store.supports_cypher:
        console.print(f"Backend '{backend}' does not support Cypher. Use --backend kuzu.")
        raise typer.Exit(1)

    console.print(f"[dim]Executing: {query_str}[/dim]")
    t0 = _time.perf_counter()
    try:
        results = store.query_cypher(query_str)
    except Exception as e:
        console.print(f"Query failed: {e}")
        raise typer.Exit(1)
    elapsed = _time.perf_counter() - t0

    if not results:
        console.print(f"(no results) [{elapsed*1000:.1f}ms]")
        store.close()
        return

    # Display as table
    from rich.table import Table
    columns = list(results[0].keys())
    table = Table(title=f"Results ({min(len(results), limit)} of {len(results)} rows, {elapsed*1000:.1f}ms)")
    for col in columns:
        table.add_column(col, overflow="fold")

    for row in results[:limit]:
        table.add_row(*[str(row.get(c, "")) for c in columns])

    console.print(table)
    store.close()


@app.command(name="find")
def find_cmd(
    what: Annotated[str, typer.Argument(help="What to find: endpoints, guards, entities, components, unprotected, flow")],
    path: Annotated[Path, typer.Argument(help="Path to analyzed codebase")] = Path("."),
    backend: Annotated[str, typer.Option("--backend", "-b", help="Graph backend")] = "kuzu",
    node_id: Annotated[Optional[str], typer.Option("--from", "-f", help="Starting node ID (for flow)")] = None,
    hops: Annotated[int, typer.Option("--hops", "-h", help="Traversal depth")] = 3,
    config: Annotated[Optional[Path], typer.Option("--config", "-c")] = None,
) -> None:
    """Run preset graph queries: endpoints, guards, entities, components, unprotected, flow."""
    import time as _time

    store = _load_graph_backend(path, backend, config)

    _PRESETS = {
        "endpoints": {
            "cypher": "MATCH (e:CodeNode) WHERE e.kind = 'endpoint' RETURN e.id, e.label, e.properties ORDER BY e.label",
            "fallback_kind": "endpoint",
            "desc": "All API endpoints",
        },
        "guards": {
            "cypher": "MATCH (g:CodeNode) WHERE g.kind = 'guard' RETURN g.id, g.label, g.properties ORDER BY g.label",
            "fallback_kind": "guard",
            "desc": "All auth guards",
        },
        "entities": {
            "cypher": "MATCH (e:CodeNode) WHERE e.kind = 'entity' RETURN e.id, e.label, e.properties ORDER BY e.label",
            "fallback_kind": "entity",
            "desc": "All data entities",
        },
        "components": {
            "cypher": "MATCH (c:CodeNode) WHERE c.kind = 'component' RETURN c.id, c.label, c.properties ORDER BY c.label",
            "fallback_kind": "component",
            "desc": "All frontend components",
        },
        "unprotected": {
            "cypher": (
                "MATCH (e:CodeNode) WHERE e.kind = 'endpoint' "
                "AND NOT EXISTS { MATCH (g:CodeNode)-[:CODE_EDGE]->(e) WHERE g.kind = 'guard' } "
                "RETURN e.id, e.label, e.properties ORDER BY e.label"
            ),
            "desc": "Endpoints without auth guards",
        },
        "flow": {
            "cypher_template": (
                "MATCH (start:CodeNode {{id: $node_id}})-[e:CODE_EDGE*1..{hops}]->(target:CodeNode) "
                "RETURN DISTINCT target.id, target.kind, target.label"
            ),
            "desc": "Trace flow from a node",
        },
    }

    if what not in _PRESETS:
        console.print(f"Unknown query: '{what}'. Available: {', '.join(_PRESETS.keys())}")
        raise typer.Exit(1)

    preset = _PRESETS[what]
    console.print(f"[bold]{preset['desc']}[/bold]")

    t0 = _time.perf_counter()

    if store.supports_cypher:
        # Use Cypher for graph DB backends
        if what == "flow":
            if not node_id:
                console.print("--from/-f required for flow query. Pass a node ID.")
                raise typer.Exit(1)
            cypher_q = preset["cypher_template"].format(hops=hops)
            try:
                results = store.query_cypher(cypher_q, {"node_id": node_id})
            except Exception:
                # Fallback: use neighbors traversal
                results = _flow_fallback(store, node_id, hops)
        elif what == "unprotected":
            try:
                results = store.query_cypher(preset["cypher"])
            except Exception:
                results = _unprotected_fallback(store)
        else:
            results = store.query_cypher(preset["cypher"])
    else:
        # Fallback for non-Cypher backends (NetworkX, SQLite)
        from code_intelligence.models.graph import NodeKind
        if what == "flow":
            results = _flow_fallback(store, node_id, hops)
        elif what == "unprotected":
            results = _unprotected_fallback(store)
        else:
            kind_str = preset.get("fallback_kind", what)
            try:
                kind = NodeKind(kind_str)
                nodes = store.nodes_by_kind(kind)
                results = [{"id": n.id, "label": n.label, "properties": str(n.properties)} for n in nodes]
            except ValueError:
                results = []

    elapsed = _time.perf_counter() - t0

    if not results:
        console.print(f"(no results) [{elapsed*1000:.1f}ms]")
        store.close()
        return

    from rich.table import Table
    columns = list(results[0].keys())
    table = Table(title=f"{len(results)} results ({elapsed*1000:.1f}ms)")
    for col in columns:
        table.add_column(col, overflow="fold")
    for row in results[:100]:
        table.add_row(*[str(row.get(c, "")) for c in columns])
    console.print(table)
    store.close()


def _flow_fallback(store, node_id: str | None, hops: int) -> list[dict]:
    """Trace flow using iterative neighbor traversal (non-Cypher fallback)."""
    if not node_id:
        return []
    visited: set[str] = set()
    frontier = {node_id}
    results = []
    for depth in range(1, hops + 1):
        next_frontier: set[str] = set()
        for nid in frontier:
            for neighbor in store.neighbors(nid, direction="out"):
                if neighbor not in visited:
                    visited.add(neighbor)
                    next_frontier.add(neighbor)
                    node = store.get_node(neighbor)
                    if node:
                        results.append({"id": node.id, "kind": node.kind.value, "label": node.label})
        frontier = next_frontier
    return results


def _unprotected_fallback(store) -> list[dict]:
    """Find unprotected endpoints using public API (non-Cypher fallback)."""
    from code_intelligence.models.graph import NodeKind, EdgeKind
    endpoints = store.nodes_by_kind(NodeKind.ENDPOINT)
    guards = store.edges_by_kind(EdgeKind.PROTECTS)
    protected_ids = {e.target for e in guards}
    return [
        {"id": e.id, "label": e.label, "properties": str(e.properties)}
        for e in endpoints if e.id not in protected_ids
    ]


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
        size_kb = out_path.stat().st_size / 1024
        console.print(f"   Size: {size_kb:.1f} KB — open in any browser, no server needed")
    else:
        diagram = engine.generate(view)
        content = engine.render(diagram, format)
        if output:
            output.write_text(content)
            console.print(f"Flow diagram ({view}) saved to [bold]{output}[/bold]")
        else:
            console.print(content)

    store.close()


if __name__ == "__main__":
    app()
