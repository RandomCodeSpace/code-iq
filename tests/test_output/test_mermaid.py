"""Tests for Mermaid renderer."""

from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind
from code_intelligence.output.mermaid import MermaidRenderer


def _build_sample_store() -> GraphStore:
    store = GraphStore()
    store.add_node(GraphNode(id="module:orders", kind=NodeKind.MODULE, label="orders", module="module:orders"))
    store.add_node(GraphNode(
        id="endpoint:GET:/api/orders",
        kind=NodeKind.ENDPOINT,
        label="GET /api/orders",
        module="module:orders",
    ))
    store.add_node(GraphNode(
        id="entity:Order",
        kind=NodeKind.ENTITY,
        label="Order",
        module="module:orders",
    ))
    store.add_node(GraphNode(
        id="topic:order-events",
        kind=NodeKind.TOPIC,
        label="order-events",
        module="module:orders",
    ))
    store.add_edge(GraphEdge(
        source="module:orders",
        target="endpoint:GET:/api/orders",
        kind=EdgeKind.CONTAINS,
    ))
    store.add_edge(GraphEdge(
        source="endpoint:GET:/api/orders",
        target="entity:Order",
        kind=EdgeKind.QUERIES,
        label="queries",
    ))
    store.add_edge(GraphEdge(
        source="module:orders",
        target="topic:order-events",
        kind=EdgeKind.PRODUCES,
        label="produces",
    ))
    return store


def test_mermaid_render():
    store = _build_sample_store()
    renderer = MermaidRenderer()
    output = renderer.render(store)
    assert output.startswith("graph LR")
    assert "order_events" in output or "order-events" in output  # sanitized ID
    assert "GET /api/orders" in output


def test_mermaid_contains_subgraphs():
    store = _build_sample_store()
    renderer = MermaidRenderer()
    output = renderer.render(store, cluster_by="module")
    assert "subgraph" in output
