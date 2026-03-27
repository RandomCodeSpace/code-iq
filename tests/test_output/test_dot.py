"""Tests for DOT renderer."""

from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind
from code_intelligence.output.dot import DotRenderer


def _build_sample_store() -> GraphStore:
    store = GraphStore()
    store.add_node(GraphNode(id="module:orders", kind=NodeKind.MODULE, label="orders"))
    store.add_node(GraphNode(id="entity:Order", kind=NodeKind.ENTITY, label="Order"))
    store.add_edge(GraphEdge(source="module:orders", target="entity:Order", kind=EdgeKind.CONTAINS))
    return store


def test_dot_render():
    store = _build_sample_store()
    renderer = DotRenderer()
    output = renderer.render(store)
    assert "digraph" in output
    assert "orders" in output
    assert "Order" in output


def test_dot_rankdir():
    store = _build_sample_store()
    renderer = DotRenderer()
    output = renderer.render(store)
    assert "rankdir" in output
