"""Tests for GraphStore."""

from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)


def _make_node(id: str, kind: NodeKind = NodeKind.CLASS, label: str = "") -> GraphNode:
    return GraphNode(id=id, kind=kind, label=label or id)


def _make_edge(source: str, target: str, kind: EdgeKind = EdgeKind.CALLS) -> GraphEdge:
    return GraphEdge(source=source, target=target, kind=kind)


def test_add_and_get_node():
    store = GraphStore()
    node = _make_node("class:Foo", NodeKind.CLASS, "Foo")
    store.add_node(node)

    retrieved = store.get_node("class:Foo")
    assert retrieved is not None
    assert retrieved.id == "class:Foo"
    assert retrieved.kind == NodeKind.CLASS
    assert store.node_count == 1


def test_add_and_get_edges():
    store = GraphStore()
    store.add_node(_make_node("A"))
    store.add_node(_make_node("B"))
    store.add_edge(_make_edge("A", "B", EdgeKind.CALLS))

    edges = store.get_edges_between("A", "B")
    assert len(edges) == 1
    assert edges[0].kind == EdgeKind.CALLS
    assert store.edge_count == 1


def test_nodes_by_kind():
    store = GraphStore()
    store.add_node(_make_node("e1", NodeKind.ENDPOINT, "GET /users"))
    store.add_node(_make_node("c1", NodeKind.CLASS, "UserController"))
    store.add_node(_make_node("e2", NodeKind.ENDPOINT, "POST /users"))

    endpoints = store.nodes_by_kind(NodeKind.ENDPOINT)
    assert len(endpoints) == 2
    classes = store.nodes_by_kind(NodeKind.CLASS)
    assert len(classes) == 1


def test_neighbors():
    store = GraphStore()
    store.add_node(_make_node("A"))
    store.add_node(_make_node("B"))
    store.add_node(_make_node("C"))
    store.add_edge(_make_edge("A", "B", EdgeKind.CALLS))
    store.add_edge(_make_edge("C", "A", EdgeKind.DEPENDS_ON))

    out_neighbors = store.neighbors("A", direction="out")
    assert "B" in out_neighbors
    in_neighbors = store.neighbors("A", direction="in")
    assert "C" in in_neighbors
    all_neighbors = store.neighbors("A", direction="both")
    assert "B" in all_neighbors and "C" in all_neighbors


def test_ego_graph():
    store = GraphStore()
    for n in ["A", "B", "C", "D", "E"]:
        store.add_node(_make_node(n))
    store.add_edge(_make_edge("A", "B"))
    store.add_edge(_make_edge("B", "C"))
    store.add_edge(_make_edge("C", "D"))
    store.add_edge(_make_edge("D", "E"))

    ego = store.ego("A", radius=2)
    assert ego.node_count == 3  # A, B, C
    assert ego.get_node("D") is None


def test_subgraph():
    store = GraphStore()
    for n in ["A", "B", "C"]:
        store.add_node(_make_node(n))
    store.add_edge(_make_edge("A", "B"))
    store.add_edge(_make_edge("B", "C"))

    sub = store.subgraph({"A", "B"})
    assert sub.node_count == 2
    assert sub.edge_count == 1


def test_filter():
    store = GraphStore()
    store.add_node(_make_node("e1", NodeKind.ENDPOINT))
    store.add_node(_make_node("c1", NodeKind.CLASS))
    store.add_edge(_make_edge("c1", "e1", EdgeKind.EXPOSES))

    filtered = store.filter(node_filter=lambda n: n.kind == NodeKind.ENDPOINT)
    assert filtered.node_count == 1
    assert filtered.edge_count == 0  # c1 removed, so edge is gone


def test_find_cycles():
    store = GraphStore()
    for n in ["A", "B", "C"]:
        store.add_node(_make_node(n))
    store.add_edge(_make_edge("A", "B"))
    store.add_edge(_make_edge("B", "C"))
    store.add_edge(_make_edge("C", "A"))

    cycles = store.find_cycles()
    assert len(cycles) >= 1


def test_to_and_from_model():
    store = GraphStore()
    store.add_node(_make_node("A", NodeKind.MODULE, "module-a"))
    store.add_node(_make_node("B", NodeKind.MODULE, "module-b"))
    store.add_edge(_make_edge("A", "B", EdgeKind.DEPENDS_ON))

    model = store.to_model()
    assert len(model.nodes) == 2
    assert len(model.edges) == 1
    assert model.metadata["stats"]["total_nodes"] == 2

    new_store = GraphStore()
    new_store.from_model(model)
    assert new_store.node_count == 2
    assert new_store.edge_count == 1
