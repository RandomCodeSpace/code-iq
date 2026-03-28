"""Tests for graph/backends/sqlite_backend.py — SQLite graph backend."""

from __future__ import annotations

from pathlib import Path

import pytest

from osscodeiq.graph.backends.sqlite_backend import SqliteGraphBackend
from osscodeiq.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind


@pytest.fixture
def backend(tmp_path: Path) -> SqliteGraphBackend:
    db = tmp_path / "test.db"
    return SqliteGraphBackend(str(db))


@pytest.fixture
def memory_backend() -> SqliteGraphBackend:
    return SqliteGraphBackend(":memory:")


def _node(id: str, kind: NodeKind = NodeKind.CLASS, label: str | None = None) -> GraphNode:
    return GraphNode(id=id, kind=kind, label=label or id)


def _edge(src: str, tgt: str, kind: EdgeKind = EdgeKind.CALLS) -> GraphEdge:
    return GraphEdge(source=src, target=tgt, kind=kind)


def _populate(b: SqliteGraphBackend) -> None:
    """Add a small graph: A->B->C, A->C."""
    b.add_node(_node("A"))
    b.add_node(_node("B"))
    b.add_node(_node("C"))
    b.add_edge(_edge("A", "B"))
    b.add_edge(_edge("B", "C"))
    b.add_edge(_edge("A", "C"))


# ---------- Node operations ----------


class TestNodeOperations:
    def test_add_and_get_node(self, backend: SqliteGraphBackend):
        n = _node("x:1", NodeKind.ENDPOINT, "GET /api")
        backend.add_node(n)
        retrieved = backend.get_node("x:1")
        assert retrieved is not None
        assert retrieved.id == "x:1"
        assert retrieved.kind == NodeKind.ENDPOINT
        assert retrieved.label == "GET /api"

    def test_has_node(self, backend: SqliteGraphBackend):
        backend.add_node(_node("a"))
        assert backend.has_node("a") is True
        assert backend.has_node("nonexistent") is False

    def test_get_nonexistent_node(self, backend: SqliteGraphBackend):
        assert backend.get_node("missing") is None

    def test_node_count(self, backend: SqliteGraphBackend):
        assert backend.node_count == 0
        backend.add_node(_node("a"))
        backend.add_node(_node("b"))
        assert backend.node_count == 2

    def test_duplicate_node_ignored(self, backend: SqliteGraphBackend):
        backend.add_node(_node("a"))
        backend.add_node(_node("a"))  # INSERT OR IGNORE
        assert backend.node_count == 1

    def test_all_nodes(self, backend: SqliteGraphBackend):
        backend.add_node(_node("a"))
        backend.add_node(_node("b"))
        nodes = backend.all_nodes()
        assert len(nodes) == 2
        ids = {n.id for n in nodes}
        assert ids == {"a", "b"}

    def test_nodes_by_kind(self, backend: SqliteGraphBackend):
        backend.add_node(_node("c1", NodeKind.CLASS))
        backend.add_node(_node("e1", NodeKind.ENDPOINT))
        backend.add_node(_node("c2", NodeKind.CLASS))
        classes = backend.nodes_by_kind(NodeKind.CLASS)
        assert len(classes) == 2
        assert all(n.kind == NodeKind.CLASS for n in classes)


# ---------- Edge operations ----------


class TestEdgeOperations:
    def test_add_and_get_edge(self, backend: SqliteGraphBackend):
        backend.add_node(_node("a"))
        backend.add_node(_node("b"))
        backend.add_edge(_edge("a", "b"))
        assert backend.edge_count == 1
        edges = backend.get_edges_between("a", "b")
        assert len(edges) == 1
        assert edges[0].source == "a"
        assert edges[0].target == "b"

    def test_edge_requires_both_nodes(self, backend: SqliteGraphBackend):
        backend.add_node(_node("a"))
        # Target "b" doesn't exist
        backend.add_edge(_edge("a", "b"))
        assert backend.edge_count == 0

    def test_edge_count(self, backend: SqliteGraphBackend):
        _populate(backend)
        assert backend.edge_count == 3

    def test_all_edges(self, backend: SqliteGraphBackend):
        _populate(backend)
        edges = backend.all_edges()
        assert len(edges) == 3

    def test_edges_by_kind(self, backend: SqliteGraphBackend):
        backend.add_node(_node("a"))
        backend.add_node(_node("b"))
        backend.add_edge(_edge("a", "b", EdgeKind.CALLS))
        backend.add_edge(_edge("a", "b", EdgeKind.IMPORTS))
        calls = backend.edges_by_kind(EdgeKind.CALLS)
        assert len(calls) == 1
        assert calls[0].kind == EdgeKind.CALLS

    def test_get_edges_between_nonexistent(self, backend: SqliteGraphBackend):
        assert backend.get_edges_between("x", "y") == []


# ---------- Update operations ----------


class TestUpdateOperations:
    def test_update_node_properties(self, backend: SqliteGraphBackend):
        backend.add_node(_node("a"))
        backend.update_node_properties("a", {"layer": "backend", "score": 42})
        node = backend.get_node("a")
        assert node is not None
        assert node.properties["layer"] == "backend"
        assert node.properties["score"] == 42

    def test_update_nonexistent_node(self, backend: SqliteGraphBackend):
        # Should not raise
        backend.update_node_properties("missing", {"key": "val"})

    def test_clear(self, backend: SqliteGraphBackend):
        _populate(backend)
        assert backend.node_count == 3
        assert backend.edge_count == 3
        backend.clear()
        assert backend.node_count == 0
        assert backend.edge_count == 0


# ---------- Traversal ----------


class TestTraversal:
    def test_neighbors_both(self, backend: SqliteGraphBackend):
        _populate(backend)
        nbrs = backend.neighbors("B", direction="both")
        assert set(nbrs) == {"A", "C"}

    def test_neighbors_out(self, backend: SqliteGraphBackend):
        _populate(backend)
        nbrs = backend.neighbors("A", direction="out")
        assert set(nbrs) == {"B", "C"}

    def test_neighbors_in(self, backend: SqliteGraphBackend):
        _populate(backend)
        nbrs = backend.neighbors("C", direction="in")
        assert set(nbrs) == {"A", "B"}

    def test_neighbors_with_edge_kind_filter(self, backend: SqliteGraphBackend):
        backend.add_node(_node("x"))
        backend.add_node(_node("y"))
        backend.add_node(_node("z"))
        backend.add_edge(_edge("x", "y", EdgeKind.CALLS))
        backend.add_edge(_edge("x", "z", EdgeKind.IMPORTS))
        nbrs = backend.neighbors("x", edge_kinds={EdgeKind.CALLS}, direction="out")
        assert nbrs == ["y"]

    def test_neighbors_no_connections(self, backend: SqliteGraphBackend):
        backend.add_node(_node("isolated"))
        nbrs = backend.neighbors("isolated")
        assert nbrs == []


# ---------- Graph algorithms ----------


class TestGraphAlgorithms:
    def test_shortest_path(self, backend: SqliteGraphBackend):
        _populate(backend)
        path = backend.shortest_path("A", "C")
        assert path is not None
        assert path[0] == "A"
        assert path[-1] == "C"

    def test_shortest_path_no_path(self, backend: SqliteGraphBackend):
        backend.add_node(_node("x"))
        backend.add_node(_node("y"))
        # No edge between x and y
        path = backend.shortest_path("x", "y")
        assert path is None

    def test_shortest_path_nonexistent_node(self, backend: SqliteGraphBackend):
        backend.add_node(_node("a"))
        path = backend.shortest_path("a", "missing")
        assert path is None

    def test_find_cycles_with_cycle(self, backend: SqliteGraphBackend):
        backend.add_node(_node("a"))
        backend.add_node(_node("b"))
        backend.add_edge(_edge("a", "b"))
        backend.add_edge(_edge("b", "a"))
        cycles = backend.find_cycles()
        assert len(cycles) >= 1

    def test_find_cycles_no_cycle(self, backend: SqliteGraphBackend):
        _populate(backend)  # A->B->C, A->C — DAG
        cycles = backend.find_cycles()
        assert len(cycles) == 0

    def test_find_cycles_limit(self, backend: SqliteGraphBackend):
        # Create multiple cycles
        for i in range(5):
            a, b = f"a{i}", f"b{i}"
            backend.add_node(_node(a))
            backend.add_node(_node(b))
            backend.add_edge(_edge(a, b))
            backend.add_edge(_edge(b, a))
        cycles = backend.find_cycles(limit=2)
        assert len(cycles) <= 2


# ---------- Subgraph ----------


class TestSubgraph:
    def test_subgraph_basic(self, backend: SqliteGraphBackend):
        _populate(backend)
        sub = backend.subgraph({"A", "B"})
        assert sub.node_count == 2
        assert sub.edge_count == 1  # only A->B edge

    def test_subgraph_empty(self, backend: SqliteGraphBackend):
        _populate(backend)
        sub = backend.subgraph(set())
        assert sub.node_count == 0
        assert sub.edge_count == 0

    def test_subgraph_all_nodes(self, backend: SqliteGraphBackend):
        _populate(backend)
        sub = backend.subgraph({"A", "B", "C"})
        assert sub.node_count == 3
        assert sub.edge_count == 3


# ---------- Lifecycle ----------


class TestLifecycle:
    def test_close(self, tmp_path: Path):
        db = tmp_path / "close_test.db"
        b = SqliteGraphBackend(str(db))
        b.add_node(_node("a"))
        b.close()
        # Re-open and verify data persisted
        b2 = SqliteGraphBackend(str(db))
        assert b2.has_node("a")
        b2.close()

    def test_persistence(self, tmp_path: Path):
        db = tmp_path / "persist.db"
        b1 = SqliteGraphBackend(str(db))
        b1.add_node(_node("x"))
        b1.add_node(_node("y"))
        b1.add_edge(_edge("x", "y"))
        b1.close()

        b2 = SqliteGraphBackend(str(db))
        assert b2.node_count == 2
        assert b2.edge_count == 1
        b2.close()
