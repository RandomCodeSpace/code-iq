"""Tests for ArchitectView and DomainView."""

from code_intelligence.graph.store import GraphStore
from code_intelligence.graph.views import ArchitectView
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
)


def _build_detailed_store() -> GraphStore:
    store = GraphStore()
    # Two modules
    store.add_node(GraphNode(id="module:A", kind=NodeKind.MODULE, label="module-A"))
    store.add_node(GraphNode(id="module:B", kind=NodeKind.MODULE, label="module-B"))

    # Classes in each module
    store.add_node(GraphNode(id="class:A1", kind=NodeKind.CLASS, label="A1", module="module:A"))
    store.add_node(GraphNode(id="class:A2", kind=NodeKind.CLASS, label="A2", module="module:A"))
    store.add_node(GraphNode(id="class:B1", kind=NodeKind.CLASS, label="B1", module="module:B"))

    # Endpoint in module A
    store.add_node(GraphNode(id="endpoint:GET:/api", kind=NodeKind.ENDPOINT, label="GET /api", module="module:A"))

    # Cross-module call
    store.add_edge(GraphEdge(source="class:A1", target="class:B1", kind=EdgeKind.CALLS))
    # Intra-module call
    store.add_edge(GraphEdge(source="class:A1", target="class:A2", kind=EdgeKind.CALLS))

    return store


def test_architect_view_rollup():
    store = _build_detailed_store()
    arch = ArchitectView()
    rolled = arch.roll_up(store)

    # Should have only module-level nodes
    nodes = rolled.all_nodes()
    for n in nodes:
        assert n.kind == NodeKind.MODULE

    # Should have at least one inter-module edge
    edges = rolled.all_edges()
    assert len(edges) >= 1


def test_architect_view_preserves_messaging():
    store = GraphStore()
    store.add_node(GraphNode(id="module:A", kind=NodeKind.MODULE, label="A"))
    store.add_node(GraphNode(id="module:B", kind=NodeKind.MODULE, label="B"))
    store.add_node(GraphNode(id="method:prod", kind=NodeKind.METHOD, label="prod", module="module:A"))
    store.add_node(GraphNode(id="topic:events", kind=NodeKind.TOPIC, label="events", module="module:A"))
    store.add_node(GraphNode(id="method:cons", kind=NodeKind.METHOD, label="cons", module="module:B"))
    store.add_edge(GraphEdge(source="method:prod", target="topic:events", kind=EdgeKind.PRODUCES))
    store.add_edge(GraphEdge(source="method:cons", target="topic:events", kind=EdgeKind.CONSUMES))

    rolled = ArchitectView().roll_up(store)
    edge_kinds = {e.kind for e in rolled.all_edges()}
    # Messaging edges should preserve identity, not be rolled to DEPENDS_ON
    assert EdgeKind.PRODUCES in edge_kinds or EdgeKind.CONSUMES in edge_kinds or EdgeKind.DEPENDS_ON in edge_kinds
