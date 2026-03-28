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

    result.graph.close()

    console.print(f"Bundle created: [bold]{output}[/bold]")
    console.print(f"   Tag: {tag}")
    console.print(f"   Backend: {backend}")
    console.print(f"   Nodes: {manifest['node_count']}, Edges: {manifest['edge_count']}")
    console.print(f"   Size: {output.stat().st_size / 1024 / 1024:.1f} MB")


if __name__ == "__main__":
    app()
