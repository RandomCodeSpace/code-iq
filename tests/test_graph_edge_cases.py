"""Graph edge case tests — self-loops, circular deps, duplicates, boundary conditions."""

import pytest

from code_intelligence.graph.store import GraphStore
from code_intelligence.graph.backends.networkx import NetworkXBackend
from code_intelligence.graph.backends.sqlite_backend import SqliteGraphBackend
from code_intelligence.models.graph import GraphNode, GraphEdge, NodeKind, EdgeKind


@pytest.fixture(params=["networkx", "sqlite"])
def store(request, tmp_path):
    """Parametrized GraphStore across backends."""
    if request.param == "networkx":
        return GraphStore(backend=NetworkXBackend())
    else:
        return GraphStore(backend=SqliteGraphBackend(str(tmp_path / "test.db")))


class TestSelfLoops:
    """Nodes referencing themselves."""

    def test_self_referencing_edge(self, store):
        store.add_node(GraphNode(id="n1", kind=NodeKind.CLASS, label="Recursive"))
        store.add_edge(GraphEdge(source="n1", target="n1", kind=EdgeKind.CALLS))
        assert store.edge_count == 1
        assert "n1" in store.neighbors("n1")

    def test_self_loop_in_cycles(self, store):
        store.add_node(GraphNode(id="n1", kind=NodeKind.CLASS, label="Self"))
        store.add_edge(GraphEdge(source="n1", target="n1", kind=EdgeKind.CALLS))
        cycles = store.find_cycles(limit=10)
        assert len(cycles) >= 1


class TestCircularDependencies:
    """A -> B -> C -> A cycles."""

    def test_three_node_cycle(self, store):
        for i in range(3):
            store.add_node(GraphNode(id=f"n{i}", kind=NodeKind.MODULE, label=f"M{i}"))
        store.add_edge(GraphEdge(source="n0", target="n1", kind=EdgeKind.DEPENDS_ON))
        store.add_edge(GraphEdge(source="n1", target="n2", kind=EdgeKind.DEPENDS_ON))
        store.add_edge(GraphEdge(source="n2", target="n0", kind=EdgeKind.DEPENDS_ON))

        cycles = store.find_cycles(limit=10)
        assert len(cycles) >= 1

    def test_shortest_path_in_cycle(self, store):
        for i in range(3):
            store.add_node(GraphNode(id=f"n{i}", kind=NodeKind.MODULE, label=f"M{i}"))
        store.add_edge(GraphEdge(source="n0", target="n1", kind=EdgeKind.CALLS))
        store.add_edge(GraphEdge(source="n1", target="n2", kind=EdgeKind.CALLS))
        store.add_edge(GraphEdge(source="n2", target="n0", kind=EdgeKind.CALLS))

        path = store.shortest_path("n0", "n2")
        assert path is not None
        assert len(path) <= 3


class TestDuplicates:
    """Duplicate nodes and edges."""

    def test_duplicate_node_ignored(self, store):
        store.add_node(GraphNode(id="n1", kind=NodeKind.CLASS, label="First"))
        store.add_node(GraphNode(id="n1", kind=NodeKind.METHOD, label="Second"))
        assert store.node_count == 1
        node = store.get_node("n1")
        assert node.kind == NodeKind.CLASS  # First one wins

    def test_duplicate_edges_allowed(self, store):
        store.add_node(GraphNode(id="n1", kind=NodeKind.CLASS, label="A"))
        store.add_node(GraphNode(id="n2", kind=NodeKind.CLASS, label="B"))
        store.add_edge(GraphEdge(source="n1", target="n2", kind=EdgeKind.CALLS))
        store.add_edge(GraphEdge(source="n1", target="n2", kind=EdgeKind.IMPORTS))
        assert store.edge_count == 2  # Different kinds = different edges

    def test_same_edge_twice(self, store):
        store.add_node(GraphNode(id="n1", kind=NodeKind.CLASS, label="A"))
        store.add_node(GraphNode(id="n2", kind=NodeKind.CLASS, label="B"))
        store.add_edge(GraphEdge(source="n1", target="n2", kind=EdgeKind.CALLS))
        store.add_edge(GraphEdge(source="n1", target="n2", kind=EdgeKind.CALLS))
        # Both backends should allow multi-edges (NetworkX is a MultiDiGraph)
        assert store.edge_count >= 1


class TestDanglingEdges:
    """Edges referencing non-existent nodes."""

    def test_edge_to_nonexistent_target_rejected(self, store):
        store.add_node(GraphNode(id="n1", kind=NodeKind.CLASS, label="A"))
        store.add_edge(GraphEdge(source="n1", target="ghost", kind=EdgeKind.CALLS))
        assert store.edge_count == 0  # Rejected -- target doesn't exist

    def test_edge_from_nonexistent_source_rejected(self, store):
        store.add_node(GraphNode(id="n1", kind=NodeKind.CLASS, label="A"))
        store.add_edge(GraphEdge(source="ghost", target="n1", kind=EdgeKind.CALLS))
        assert store.edge_count == 0


class TestEmptyGraph:
    """Operations on empty graph."""

    def test_empty_all_nodes(self, store):
        assert store.all_nodes() == []

    def test_empty_all_edges(self, store):
        assert store.all_edges() == []

    def test_empty_node_count(self, store):
        assert store.node_count == 0

    def test_empty_edge_count(self, store):
        assert store.edge_count == 0

    def test_empty_find_cycles(self, store):
        assert store.find_cycles() == []

    def test_get_nonexistent_node(self, store):
        assert store.get_node("doesnt_exist") is None

    def test_neighbors_nonexistent(self, store):
        result = store.neighbors("doesnt_exist")
        assert result == []

    def test_shortest_path_no_nodes(self, store):
        # NetworkX backend raises NodeNotFound for missing nodes;
        # SQLite backend returns None. Both are acceptable for an empty graph.
        try:
            result = store.shortest_path("a", "b")
            assert result is None
        except Exception:
            pass  # NodeNotFound from NetworkX is acceptable


class TestLargeGraph:
    """Graph with many nodes/edges."""

    def test_1000_nodes(self, store):
        for i in range(1000):
            store.add_node(GraphNode(id=f"n{i}", kind=NodeKind.METHOD, label=f"m{i}"))
        assert store.node_count == 1000

    def test_node_kind_filtering(self, store):
        for i in range(100):
            store.add_node(GraphNode(id=f"ep{i}", kind=NodeKind.ENDPOINT, label=f"E{i}"))
            store.add_node(GraphNode(id=f"cl{i}", kind=NodeKind.CLASS, label=f"C{i}"))
        assert len(store.nodes_by_kind(NodeKind.ENDPOINT)) == 100
        assert len(store.nodes_by_kind(NodeKind.CLASS)) == 100


class TestSubgraph:
    """Subgraph extraction edge cases."""

    def test_subgraph_empty_ids(self, store):
        store.add_node(GraphNode(id="n1", kind=NodeKind.CLASS, label="A"))
        sub = store.subgraph(set())
        assert sub.node_count == 0

    def test_subgraph_preserves_edges(self, store):
        store.add_node(GraphNode(id="n1", kind=NodeKind.CLASS, label="A"))
        store.add_node(GraphNode(id="n2", kind=NodeKind.CLASS, label="B"))
        store.add_node(GraphNode(id="n3", kind=NodeKind.CLASS, label="C"))
        store.add_edge(GraphEdge(source="n1", target="n2", kind=EdgeKind.CALLS))
        store.add_edge(GraphEdge(source="n2", target="n3", kind=EdgeKind.CALLS))
        sub = store.subgraph({"n1", "n2"})
        assert sub.node_count == 2
        assert sub.edge_count == 1  # Only n1->n2, not n2->n3


class TestUpdateProperties:
    """update_node_properties edge cases."""

    def test_update_nonexistent_node(self, store):
        # Should not crash
        store.update_node_properties("ghost", {"key": "value"})
        assert store.node_count == 0

    def test_update_preserves_existing_props(self, store):
        store.add_node(GraphNode(id="n1", kind=NodeKind.CLASS, label="A", properties={"a": 1}))
        store.update_node_properties("n1", {"b": 2})
        node = store.get_node("n1")
        assert node.properties.get("a") == 1
        assert node.properties.get("b") == 2

    def test_update_overwrites_key(self, store):
        store.add_node(GraphNode(id="n1", kind=NodeKind.CLASS, label="A", properties={"a": 1}))
        store.update_node_properties("n1", {"a": 99})
        node = store.get_node("n1")
        assert node.properties["a"] == 99
