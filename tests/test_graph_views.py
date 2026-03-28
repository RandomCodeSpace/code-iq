"""Tests for graph/views.py — ArchitectView and DomainView."""

from __future__ import annotations

from osscodeiq.config import DomainMapping
from osscodeiq.graph.store import GraphStore
from osscodeiq.graph.views import ArchitectView, DomainView
from osscodeiq.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind


def _make_store() -> GraphStore:
    """Build a small graph with modules, classes, methods, and endpoints."""
    store = GraphStore()
    # Module nodes
    store.add_node(GraphNode(id="mod:a", kind=NodeKind.MODULE, label="mod.a", module="mod:a"))
    store.add_node(GraphNode(id="mod:b", kind=NodeKind.MODULE, label="mod.b", module="mod:b"))
    # Detail nodes belonging to modules
    store.add_node(GraphNode(id="cls:1", kind=NodeKind.CLASS, label="Foo", module="mod:a"))
    store.add_node(GraphNode(id="m:1", kind=NodeKind.METHOD, label="bar", module="mod:a"))
    store.add_node(GraphNode(id="ep:1", kind=NodeKind.ENDPOINT, label="GET /api", module="mod:b"))
    store.add_node(GraphNode(id="ent:1", kind=NodeKind.ENTITY, label="User", module="mod:b"))
    # Edges across modules
    store.add_edge(GraphEdge(source="cls:1", target="ep:1", kind=EdgeKind.CALLS))
    store.add_edge(GraphEdge(source="ep:1", target="ent:1", kind=EdgeKind.QUERIES))
    # Internal edge (same module)
    store.add_edge(GraphEdge(source="cls:1", target="m:1", kind=EdgeKind.CALLS))
    return store


# ---------- ArchitectView ----------


class TestArchitectView:
    def test_roll_up_creates_module_nodes_only(self):
        store = _make_store()
        view = ArchitectView()
        rolled = view.roll_up(store)
        kinds = {n.kind for n in rolled.all_nodes()}
        assert kinds == {NodeKind.MODULE}

    def test_roll_up_preserves_module_count(self):
        store = _make_store()
        view = ArchitectView()
        rolled = view.roll_up(store)
        assert rolled.node_count == 2  # mod:a and mod:b

    def test_roll_up_cross_module_edge(self):
        store = _make_store()
        view = ArchitectView()
        rolled = view.roll_up(store)
        edges = rolled.all_edges()
        # cls:1 (mod:a) -> ep:1 (mod:b) should become mod:a -> mod:b
        sources = {e.source for e in edges}
        targets = {e.target for e in edges}
        assert "mod:a" in sources
        assert "mod:b" in targets

    def test_roll_up_removes_intra_module_edges(self):
        store = _make_store()
        view = ArchitectView()
        rolled = view.roll_up(store)
        edges = rolled.all_edges()
        # No self-loops at module level
        for e in edges:
            assert e.source != e.target

    def test_roll_up_summary_properties(self):
        store = _make_store()
        view = ArchitectView()
        rolled = view.roll_up(store)
        mod_a = rolled.get_node("mod:a")
        assert mod_a is not None
        assert "class_count" in mod_a.properties
        assert mod_a.properties["class_count"] == 1
        assert mod_a.properties["method_count"] == 1

    def test_roll_up_endpoint_count(self):
        store = _make_store()
        view = ArchitectView()
        rolled = view.roll_up(store)
        mod_b = rolled.get_node("mod:b")
        assert mod_b is not None
        assert mod_b.properties["endpoint_count"] == 1
        assert mod_b.properties["entity_count"] == 1

    def test_roll_up_empty_store(self):
        store = GraphStore()
        view = ArchitectView()
        rolled = view.roll_up(store)
        assert rolled.node_count == 0
        assert rolled.edge_count == 0

    def test_roll_up_messaging_edges_preserve_kind(self):
        store = GraphStore()
        store.add_node(GraphNode(id="mod:x", kind=NodeKind.MODULE, label="x", module="mod:x"))
        store.add_node(GraphNode(id="mod:y", kind=NodeKind.MODULE, label="y", module="mod:y"))
        store.add_node(GraphNode(id="p:1", kind=NodeKind.CLASS, label="Producer", module="mod:x"))
        store.add_node(GraphNode(id="c:1", kind=NodeKind.CLASS, label="Consumer", module="mod:y"))
        store.add_edge(GraphEdge(source="p:1", target="c:1", kind=EdgeKind.PRODUCES))

        view = ArchitectView()
        rolled = view.roll_up(store)
        edge_kinds = {e.kind for e in rolled.all_edges()}
        assert EdgeKind.PRODUCES in edge_kinds

    def test_roll_up_determinism(self):
        """Two runs produce identical output."""
        for _ in range(2):
            store = _make_store()
            view = ArchitectView()
            rolled = view.roll_up(store)
            assert rolled.node_count == 2
            edges = rolled.all_edges()
            edge_set = {(e.source, e.target, e.kind) for e in edges}
            assert len(edge_set) == len(edges)  # no duplicates


# ---------- DomainView ----------


class TestDomainView:
    def _module_store(self) -> GraphStore:
        store = GraphStore()
        store.add_node(GraphNode(id="mod:orders", kind=NodeKind.MODULE, label="orders"))
        store.add_node(GraphNode(id="mod:payments", kind=NodeKind.MODULE, label="payments"))
        store.add_node(GraphNode(id="mod:logging", kind=NodeKind.MODULE, label="logging"))
        store.add_edge(GraphEdge(source="mod:orders", target="mod:payments", kind=EdgeKind.DEPENDS_ON))
        store.add_edge(GraphEdge(source="mod:orders", target="mod:logging", kind=EdgeKind.DEPENDS_ON))
        return store

    def test_domain_roll_up_groups_modules(self):
        store = self._module_store()
        mappings = [
            DomainMapping(name="Commerce", modules=["mod:orders", "mod:payments"]),
        ]
        view = DomainView(mappings)
        rolled = view.roll_up(store)
        domain_node = rolled.get_node("domain:Commerce")
        assert domain_node is not None
        assert domain_node.properties["module_count"] == 2

    def test_domain_unmapped_kept(self):
        store = self._module_store()
        mappings = [
            DomainMapping(name="Commerce", modules=["mod:orders", "mod:payments"]),
        ]
        view = DomainView(mappings)
        rolled = view.roll_up(store)
        # mod:logging is unmapped, should remain
        logging_node = rolled.get_node("mod:logging")
        assert logging_node is not None

    def test_domain_intra_domain_edges_skipped(self):
        store = self._module_store()
        mappings = [
            DomainMapping(name="Commerce", modules=["mod:orders", "mod:payments"]),
        ]
        view = DomainView(mappings)
        rolled = view.roll_up(store)
        # orders -> payments both map to Commerce, so that edge is a self-loop and skipped
        for e in rolled.all_edges():
            assert e.source != e.target

    def test_domain_cross_domain_edge(self):
        store = self._module_store()
        mappings = [
            DomainMapping(name="Commerce", modules=["mod:orders", "mod:payments"]),
        ]
        view = DomainView(mappings)
        rolled = view.roll_up(store)
        # orders -> logging should become domain:Commerce -> mod:logging
        edges = rolled.all_edges()
        assert len(edges) >= 1
        assert any(e.source == "domain:Commerce" and e.target == "mod:logging" for e in edges)

    def test_domain_empty_mappings(self):
        store = self._module_store()
        view = DomainView([])
        rolled = view.roll_up(store)
        # All nodes kept as-is
        assert rolled.node_count == 3

    def test_domain_prefix_matching(self):
        store = GraphStore()
        store.add_node(GraphNode(id="com.example.orders.service", kind=NodeKind.MODULE, label="svc"))
        mappings = [
            DomainMapping(name="Orders", modules=["com.example.orders"]),
        ]
        view = DomainView(mappings)
        rolled = view.roll_up(store)
        assert rolled.get_node("domain:Orders") is not None
