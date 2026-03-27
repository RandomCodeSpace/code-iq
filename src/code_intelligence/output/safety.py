"""Graph size safety guard for the code intelligence CLI."""

from __future__ import annotations

import typer
from rich.console import Console

from code_intelligence.graph.store import GraphStore


def check_graph_size(
    store: GraphStore,
    max_nodes: int,
    console: Console,
) -> None:
    """Abort with a helpful message if *store* exceeds *max_nodes*.

    Parameters
    ----------
    store:
        The graph store to check.
    max_nodes:
        Maximum number of nodes allowed before the safety guard fires.
    console:
        Rich console used to print the error and suggestions.

    Raises
    ------
    typer.Exit
        If the node count exceeds *max_nodes*.
    """
    count = store.node_count
    if count <= max_nodes:
        return

    console.print(
        f"\n[bold red]Error:[/bold red] Graph contains "
        f"[bold]{count:,}[/bold] nodes, which exceeds the "
        f"safety limit of [bold]{max_nodes:,}[/bold].\n"
    )
    console.print("[bold yellow]Suggestions to reduce the graph size:[/bold yellow]\n")
    console.print(
        "  1. Use [cyan]--view architect[/cyan] to collapse detail "
        "nodes into module-level nodes."
    )
    console.print(
        "  2. Use [cyan]--focus \"node_id\" --hops 1[/cyan] to restrict "
        "output to a small neighbourhood."
    )
    console.print(
        "  3. Use [cyan]--module <name>[/cyan] to filter by module."
    )
    console.print(
        "  4. Use [cyan]--max-nodes N[/cyan] to override this limit "
        "(e.g. [cyan]--max-nodes 2000[/cyan]).\n"
    )

    raise typer.Exit(code=1)
