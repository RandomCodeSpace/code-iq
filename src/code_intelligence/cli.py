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
    config: Annotated[Optional[Path], typer.Option("--config", "-c", help="Path to config file")] = None,
) -> None:
    """Analyze a codebase and build the code intelligence graph."""
    from code_intelligence.analyzer import Analyzer

    cfg = _load_config(config)
    cfg.analysis.parallelism = parallelism
    cfg.analysis.incremental = incremental

    analyzer = Analyzer(cfg)
    result = analyzer.run(path.resolve(), incremental=incremental)

    console.print(f"[green]Analysis complete.[/green]")
    console.print(f"  Nodes: {result.graph.node_count}")
    console.print(f"  Edges: {result.graph.edge_count}")


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
        console.print("[red]No analysis cache found. Run 'code-intelligence analyze' first.[/red]")
        raise typer.Exit(1)

    from code_intelligence.cache.store import CacheStore
    cache = CacheStore(cache_path)
    store = cache.load_full_graph()

    # Apply view transformation
    if view == "architect":
        store = ArchitectView().roll_up(store)
    elif view == "domain":
        store = DomainView(cfg.domains).roll_up(store)

    # Apply filters via query builder
    query = GraphQuery(store)
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
        console.print(f"[red]Unknown format: {format}[/red]")
        raise typer.Exit(1)

    if output:
        output.write_text(content)
        console.print(f"[green]Graph written to {output}[/green]")
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
        console.print("[red]No analysis cache found. Run 'code-intelligence analyze' first.[/red]")
        raise typer.Exit(1)

    from code_intelligence.cache.store import CacheStore
    from code_intelligence.graph.query import GraphQuery

    cache = CacheStore(cache_path)
    store = cache.load_full_graph()
    q = GraphQuery(store)

    if consumers_of:
        result = q.consumers_of(consumers_of).execute()
        _print_query_result(result, f"Consumers of '{consumers_of}'")
    elif producers_of:
        result = q.producers_of(producers_of).execute()
        _print_query_result(result, f"Producers of '{producers_of}'")
    elif callers_of:
        result = q.callers_of(callers_of).execute()
        _print_query_result(result, f"Callers of '{callers_of}'")
    elif dependencies_of:
        result = q.dependencies_of(dependencies_of).execute()
        _print_query_result(result, f"Dependencies of '{dependencies_of}'")
    elif dependents_of:
        result = q.dependents_of(dependents_of).execute()
        _print_query_result(result, f"Dependents of '{dependents_of}'")
    elif cycles:
        cycle_list = store.find_cycles()
        console.print(f"[bold]Found {len(cycle_list)} cycles:[/bold]")
        for i, cycle in enumerate(cycle_list[:20], 1):
            console.print(f"  {i}. {' → '.join(cycle)}")
        if len(cycle_list) > 20:
            console.print(f"  ... and {len(cycle_list) - 20} more")
    else:
        console.print("[yellow]Specify a query option. Use --help for available queries.[/yellow]")


def _print_query_result(store: "GraphStore", title: str) -> None:
    from code_intelligence.graph.store import GraphStore
    nodes = store.all_nodes()
    console.print(f"[bold]{title} ({len(nodes)} results):[/bold]")
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
            cache_path.unlink()
            console.print("[green]Cache cleared.[/green]")
        else:
            console.print("[yellow]No cache found.[/yellow]")
    elif action == "stats":
        if not cache_path.exists():
            console.print("[yellow]No cache found. Run 'code-intelligence analyze' first.[/yellow]")
            return
        from code_intelligence.cache.store import CacheStore
        cs = CacheStore(cache_path)
        stats = cs.get_stats()
        console.print("[bold]Cache Statistics:[/bold]")
        for key, value in stats.items():
            console.print(f"  {key}: {value}")
    else:
        console.print(f"[red]Unknown action: {action}. Use 'stats' or 'clear'.[/red]")


@app.command()
def plugins(
    action: Annotated[str, typer.Argument(help="Action: list, info")] = "list",
    name: Annotated[Optional[str], typer.Argument(help="Plugin name (for info)")] = None,
) -> None:
    """Manage detector plugins."""
    from code_intelligence.detectors.registry import DetectorRegistry

    registry = DetectorRegistry()
    registry.load_builtin_detectors()
    registry.load_plugin_detectors()

    if action == "list":
        detectors = registry.all_detectors()
        console.print(f"[bold]Registered detectors ({len(detectors)}):[/bold]")
        for det in detectors:
            langs = ", ".join(det.supported_languages)
            console.print(f"  {det.name} [{langs}]")
    elif action == "info" and name:
        det = registry.get(name)
        if det:
            console.print(f"[bold]{det.name}[/bold]")
            console.print(f"  Languages: {', '.join(det.supported_languages)}")
        else:
            console.print(f"[red]Detector '{name}' not found.[/red]")
    else:
        console.print("[yellow]Use 'list' or 'info <name>'.[/yellow]")


if __name__ == "__main__":
    app()
