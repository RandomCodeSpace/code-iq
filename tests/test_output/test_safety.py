"""Tests for output safety guard."""

import pytest

from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import GraphNode, NodeKind
from code_intelligence.output.safety import check_graph_size


def test_check_graph_size_under_limit():
    store = GraphStore()
    for i in range(5):
        store.add_node(GraphNode(id=f"n{i}", kind=NodeKind.CLASS, label=f"n{i}"))
    # Should not raise
    from rich.console import Console
    check_graph_size(store, max_nodes=10, console=Console(quiet=True))


def test_check_graph_size_over_limit():
    store = GraphStore()
    for i in range(20):
        store.add_node(GraphNode(id=f"n{i}", kind=NodeKind.CLASS, label=f"n{i}"))

    from rich.console import Console
    from click.exceptions import Exit
    with pytest.raises(Exit):
        check_graph_size(store, max_nodes=5, console=Console(quiet=True))
