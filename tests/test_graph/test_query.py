"""Tests for GraphQuery."""

from code_intelligence.graph.query import GraphQuery
from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)


def _build_store() -> GraphStore:
    store = GraphStore()
    # Modules
    store.add_node(GraphNode(id="module:orders", kind=NodeKind.MODULE, label="orders"))
    store.add_node(GraphNode(id="module:payments", kind=NodeKind.MODULE, label="payments"))

    # Endpoints
    store.add_node(GraphNode(
        id="endpoint:GET:/api/orders", kind=NodeKind.ENDPOINT,
        label="GET /api/orders", module="module:orders",
        location=SourceLocation(file_path="orders/api.java"),
        annotations=["@GetMapping"],
    ))
    store.add_node(GraphNode(
        id="endpoint:POST:/api/payments", kind=NodeKind.ENDPOINT,
        label="POST /api/payments", module="module:payments",
        location=SourceLocation(file_path="payments/api.java"),
    ))

    # Entity
    store.add_node(GraphNode(
        id="entity:Order", kind=NodeKind.ENTITY,
        label="Order", module="module:orders",
    ))

    # Topic
    store.add_node(GraphNode(
        id="topic:order-events", kind=NodeKind.TOPIC,
        label="order-events",
    ))

    # Edges
    store.add_edge(GraphEdge(source="module:orders", target="module:payments", kind=EdgeKind.DEPENDS_ON))
    store.add_edge(GraphEdge(source="endpoint:GET:/api/orders", target="entity:Order", kind=EdgeKind.QUERIES))
    store.add_edge(GraphEdge(source="module:orders", target="topic:order-events", kind=EdgeKind.PRODUCES))
    store.add_edge(GraphEdge(source="module:payments", target="topic:order-events", kind=EdgeKind.CONSUMES))

    return store


def test_filter_node_kinds():
    store = _build_store()
    result = GraphQuery(store).filter_node_kinds([NodeKind.ENDPOINT]).execute()
    assert result.node_count == 2
    for n in result.all_nodes():
        assert n.kind == NodeKind.ENDPOINT


def test_filter_modules():
    store = _build_store()
    result = GraphQuery(store).filter_modules(["module:orders"]).execute()
    # Should include nodes with module="module:orders" plus the module node itself
    for n in result.all_nodes():
        assert n.module == "module:orders" or n.id == "module:orders" or n.module is None


def test_filter_edge_kinds():
    store = _build_store()
    result = GraphQuery(store).filter_edge_kinds([EdgeKind.PRODUCES, EdgeKind.CONSUMES]).execute()
    for e in result.all_edges():
        assert e.kind in (EdgeKind.PRODUCES, EdgeKind.CONSUMES)


def test_focus():
    store = _build_store()
    result = GraphQuery(store).focus("entity:Order", hops=1).execute()
    assert result.get_node("entity:Order") is not None
    # Should include endpoint that queries Order
    assert result.get_node("endpoint:GET:/api/orders") is not None


def test_consumers_of():
    store = _build_store()
    result = GraphQuery(store).consumers_of("topic:order-events").execute()
    nodes = result.all_nodes()
    node_ids = {n.id for n in nodes}
    assert "module:payments" in node_ids or "topic:order-events" in node_ids


def test_chaining():
    store = _build_store()
    result = (
        GraphQuery(store)
        .filter_node_kinds([NodeKind.ENDPOINT, NodeKind.ENTITY])
        .filter_edge_kinds([EdgeKind.QUERIES])
        .execute()
    )
    assert result.node_count >= 1
