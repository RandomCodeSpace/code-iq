"""Tests for KuzuDB graph backend."""
from __future__ import annotations

import pytest

from osscodeiq.graph.backends.kuzu import KuzuBackend
from osscodeiq.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation


@pytest.fixture
def backend(tmp_path):
    """Create a fresh KuzuDB backend."""
    db_path = str(tmp_path / "test.kuzu")
    b = KuzuBackend(db_path)
    return b


@pytest.fixture
def populated_backend(backend):
    """Backend with test data."""
    backend.add_node(GraphNode(id="cls:foo", kind=NodeKind.CLASS, label="Foo", module="mod.a",
        location=SourceLocation(file_path="src/foo.py", line_start=1, line_end=10)))
    backend.add_node(GraphNode(id="cls:bar", kind=NodeKind.CLASS, label="Bar", module="mod.a"))
    backend.add_node(GraphNode(id="ep:api", kind=NodeKind.ENDPOINT, label="GET /api", module="mod.b"))
    backend.add_node(GraphNode(id="ent:user", kind=NodeKind.ENTITY, label="User", module="mod.c",
        properties={"table": "users"}))
    backend.add_edge(GraphEdge(source="cls:foo", target="cls:bar", kind=EdgeKind.EXTENDS))
    backend.add_edge(GraphEdge(source="ep:api", target="cls:foo", kind=EdgeKind.CALLS))
    backend.add_edge(GraphEdge(source="cls:foo", target="ent:user", kind=EdgeKind.QUERIES))
    backend.add_edge(GraphEdge(source="cls:bar", target="cls:foo", kind=EdgeKind.IMPORTS))
    return backend


# ---------------------------------------------------------------------------
# 1. Empty backend
# ---------------------------------------------------------------------------
def test_empty_backend(backend):
    assert backend.node_count == 0
    assert backend.edge_count == 0


# ---------------------------------------------------------------------------
# 2. add_node
# ---------------------------------------------------------------------------
def test_add_node(backend):
    backend.add_node(GraphNode(id="cls:x", kind=NodeKind.CLASS, label="X"))
    assert backend.node_count == 1
    assert backend.has_node("cls:x")


# ---------------------------------------------------------------------------
# 3. add_node with properties
# ---------------------------------------------------------------------------
def test_add_node_with_properties(backend):
    props = {"framework": "django", "version": "4.2"}
    backend.add_node(GraphNode(id="cls:x", kind=NodeKind.CLASS, label="X", properties=props))
    node = backend.get_node("cls:x")
    assert node is not None
    assert node.properties == props


# ---------------------------------------------------------------------------
# 4. add_node with location
# ---------------------------------------------------------------------------
def test_add_node_with_location(backend):
    loc = SourceLocation(file_path="src/main.py", line_start=5, line_end=20)
    backend.add_node(GraphNode(id="cls:x", kind=NodeKind.CLASS, label="X", location=loc))
    node = backend.get_node("cls:x")
    assert node is not None
    assert node.location is not None
    assert node.location.file_path == "src/main.py"
    assert node.location.line_start == 5
    assert node.location.line_end == 20


# ---------------------------------------------------------------------------
# 5. add_node duplicate
# ---------------------------------------------------------------------------
def test_add_node_duplicate(backend):
    backend.add_node(GraphNode(id="cls:x", kind=NodeKind.CLASS, label="X"))
    backend.add_node(GraphNode(id="cls:x", kind=NodeKind.CLASS, label="X_dup"))
    assert backend.node_count == 1
    node = backend.get_node("cls:x")
    assert node is not None
    assert node.label == "X"  # first insert wins


# ---------------------------------------------------------------------------
# 6. add_edge
# ---------------------------------------------------------------------------
def test_add_edge(backend):
    backend.add_node(GraphNode(id="a", kind=NodeKind.CLASS, label="A"))
    backend.add_node(GraphNode(id="b", kind=NodeKind.CLASS, label="B"))
    backend.add_edge(GraphEdge(source="a", target="b", kind=EdgeKind.EXTENDS))
    assert backend.edge_count == 1


# ---------------------------------------------------------------------------
# 7. add_edge with missing target
# ---------------------------------------------------------------------------
def test_add_edge_missing_target(backend):
    backend.add_node(GraphNode(id="a", kind=NodeKind.CLASS, label="A"))
    # target "b" does not exist — MATCH will fail silently, no edge created
    backend.add_edge(GraphEdge(source="a", target="b", kind=EdgeKind.EXTENDS))
    assert backend.edge_count == 0


# ---------------------------------------------------------------------------
# 8. node_count
# ---------------------------------------------------------------------------
def test_node_count(populated_backend):
    assert populated_backend.node_count == 4


# ---------------------------------------------------------------------------
# 9. edge_count
# ---------------------------------------------------------------------------
def test_edge_count(populated_backend):
    assert populated_backend.edge_count == 4


# ---------------------------------------------------------------------------
# 10. has_node
# ---------------------------------------------------------------------------
def test_has_node(populated_backend):
    assert populated_backend.has_node("cls:foo") is True
    assert populated_backend.has_node("nonexistent") is False


# ---------------------------------------------------------------------------
# 11. get_node
# ---------------------------------------------------------------------------
def test_get_node(populated_backend):
    node = populated_backend.get_node("cls:foo")
    assert node is not None
    assert node.id == "cls:foo"
    assert node.kind == NodeKind.CLASS
    assert node.label == "Foo"
    assert node.module == "mod.a"
    assert node.location is not None
    assert node.location.file_path == "src/foo.py"
    assert node.location.line_start == 1
    assert node.location.line_end == 10


# ---------------------------------------------------------------------------
# 12. get_node not found
# ---------------------------------------------------------------------------
def test_get_node_not_found(populated_backend):
    assert populated_backend.get_node("nonexistent") is None


# ---------------------------------------------------------------------------
# 13. all_nodes
# ---------------------------------------------------------------------------
def test_all_nodes(populated_backend):
    nodes = populated_backend.all_nodes()
    assert len(nodes) == 4
    ids = sorted(n.id for n in nodes)
    assert ids == ["cls:bar", "cls:foo", "ent:user", "ep:api"]


# ---------------------------------------------------------------------------
# 14. all_edges
# ---------------------------------------------------------------------------
def test_all_edges(populated_backend):
    edges = populated_backend.all_edges()
    assert len(edges) == 4
    kinds = sorted(e.kind.value for e in edges)
    assert kinds == ["calls", "extends", "imports", "queries"]


# ---------------------------------------------------------------------------
# 15. nodes_by_kind
# ---------------------------------------------------------------------------
def test_nodes_by_kind(populated_backend):
    classes = populated_backend.nodes_by_kind(NodeKind.CLASS)
    assert len(classes) == 2
    ids = sorted(n.id for n in classes)
    assert ids == ["cls:bar", "cls:foo"]

    endpoints = populated_backend.nodes_by_kind(NodeKind.ENDPOINT)
    assert len(endpoints) == 1
    assert endpoints[0].id == "ep:api"

    entities = populated_backend.nodes_by_kind(NodeKind.ENTITY)
    assert len(entities) == 1
    assert entities[0].id == "ent:user"


# ---------------------------------------------------------------------------
# 16. edges_by_kind
# ---------------------------------------------------------------------------
def test_edges_by_kind(populated_backend):
    extends = populated_backend.edges_by_kind(EdgeKind.EXTENDS)
    assert len(extends) == 1
    assert extends[0].source == "cls:foo"
    assert extends[0].target == "cls:bar"

    calls = populated_backend.edges_by_kind(EdgeKind.CALLS)
    assert len(calls) == 1
    assert calls[0].source == "ep:api"
    assert calls[0].target == "cls:foo"


# ---------------------------------------------------------------------------
# 17. get_edges_between
# ---------------------------------------------------------------------------
def test_get_edges_between(populated_backend):
    edges = populated_backend.get_edges_between("cls:foo", "cls:bar")
    assert len(edges) == 1
    assert edges[0].kind == EdgeKind.EXTENDS

    # No edge in this direction
    edges_rev = populated_backend.get_edges_between("ent:user", "cls:foo")
    assert len(edges_rev) == 0


# ---------------------------------------------------------------------------
# 18. neighbors both
# ---------------------------------------------------------------------------
def test_neighbors_both(populated_backend):
    # cls:foo has outgoing to cls:bar and ent:user, incoming from ep:api and cls:bar
    nbrs = populated_backend.neighbors("cls:foo", direction="both")
    assert sorted(nbrs) == ["cls:bar", "ent:user", "ep:api"]


# ---------------------------------------------------------------------------
# 19. neighbors out
# ---------------------------------------------------------------------------
def test_neighbors_out(populated_backend):
    # cls:foo -> cls:bar (EXTENDS), cls:foo -> ent:user (QUERIES)
    nbrs = populated_backend.neighbors("cls:foo", direction="out")
    assert sorted(nbrs) == ["cls:bar", "ent:user"]


# ---------------------------------------------------------------------------
# 20. neighbors in
# ---------------------------------------------------------------------------
def test_neighbors_in(populated_backend):
    # ep:api -> cls:foo (CALLS), cls:bar -> cls:foo (IMPORTS)
    nbrs = populated_backend.neighbors("cls:foo", direction="in")
    assert sorted(nbrs) == ["cls:bar", "ep:api"]


# ---------------------------------------------------------------------------
# 21. neighbors with edge_kinds filter
# ---------------------------------------------------------------------------
def test_neighbors_with_edge_kinds(populated_backend):
    # Only EXTENDS edges from cls:foo
    nbrs = populated_backend.neighbors("cls:foo", edge_kinds={EdgeKind.EXTENDS}, direction="out")
    assert nbrs == ["cls:bar"]

    # Only CALLS edges into cls:foo
    nbrs_in = populated_backend.neighbors("cls:foo", edge_kinds={EdgeKind.CALLS}, direction="in")
    assert nbrs_in == ["ep:api"]


# ---------------------------------------------------------------------------
# 22. subgraph
# ---------------------------------------------------------------------------
def test_subgraph(populated_backend):
    sub = populated_backend.subgraph({"cls:foo", "cls:bar"})
    assert sub.node_count == 2
    # Should include edges between the two selected nodes
    assert sub.edge_count == 2  # foo->bar (EXTENDS) + bar->foo (IMPORTS)
    ids = sorted(n.id for n in sub.all_nodes())
    assert ids == ["cls:bar", "cls:foo"]


# ---------------------------------------------------------------------------
# 23. find_cycles (with cycle)
# ---------------------------------------------------------------------------
def test_find_cycles(populated_backend):
    # cls:foo -> cls:bar (EXTENDS) and cls:bar -> cls:foo (IMPORTS) form a cycle
    cycles = populated_backend.find_cycles()
    assert len(cycles) >= 1
    # At least one cycle should contain both cls:foo and cls:bar
    found = False
    for cycle in cycles:
        if set(cycle) == {"cls:foo", "cls:bar"}:
            found = True
            break
    assert found, f"Expected cycle [cls:foo, cls:bar] not found in {cycles}"


# ---------------------------------------------------------------------------
# 24. find_cycles (no cycles)
# ---------------------------------------------------------------------------
def test_find_cycles_no_cycles(backend):
    backend.add_node(GraphNode(id="a", kind=NodeKind.CLASS, label="A"))
    backend.add_node(GraphNode(id="b", kind=NodeKind.CLASS, label="B"))
    backend.add_node(GraphNode(id="c", kind=NodeKind.CLASS, label="C"))
    backend.add_edge(GraphEdge(source="a", target="b", kind=EdgeKind.EXTENDS))
    backend.add_edge(GraphEdge(source="b", target="c", kind=EdgeKind.EXTENDS))
    cycles = backend.find_cycles()
    assert cycles == []


# ---------------------------------------------------------------------------
# 25. shortest_path
# ---------------------------------------------------------------------------
def test_shortest_path(populated_backend):
    # ep:api -> cls:foo -> cls:bar
    path = populated_backend.shortest_path("ep:api", "cls:bar")
    assert path is not None
    assert path[0] == "ep:api"
    assert path[-1] == "cls:bar"
    assert len(path) >= 2


# ---------------------------------------------------------------------------
# 26. shortest_path no path
# ---------------------------------------------------------------------------
def test_shortest_path_no_path(populated_backend):
    # ent:user has no outgoing edges, so no path from ent:user to ep:api
    path = populated_backend.shortest_path("ent:user", "ep:api")
    assert path is None


# ---------------------------------------------------------------------------
# 27. update_node_properties
# ---------------------------------------------------------------------------
def test_update_node_properties(populated_backend):
    populated_backend.update_node_properties("ent:user", {"role": "admin"})
    node = populated_backend.get_node("ent:user")
    assert node is not None
    assert node.properties["table"] == "users"  # original preserved
    assert node.properties["role"] == "admin"    # new property added


# ---------------------------------------------------------------------------
# 28. clear
# ---------------------------------------------------------------------------
def test_clear(populated_backend):
    assert populated_backend.node_count == 4
    assert populated_backend.edge_count == 4
    populated_backend.clear()
    assert populated_backend.node_count == 0
    assert populated_backend.edge_count == 0


# ---------------------------------------------------------------------------
# 29. query_cypher
# ---------------------------------------------------------------------------
def test_query_cypher(populated_backend):
    results = populated_backend.query_cypher("MATCH (n:CodeNode) RETURN n.id ORDER BY n.id")
    assert len(results) == 4
    ids = [r["n.id"] for r in results]
    assert ids == ["cls:bar", "cls:foo", "ent:user", "ep:api"]


# ---------------------------------------------------------------------------
# 30. query_cypher with params
# ---------------------------------------------------------------------------
def test_query_cypher_with_params(populated_backend):
    results = populated_backend.query_cypher(
        "MATCH (n:CodeNode) WHERE n.kind = $kind RETURN n.id ORDER BY n.id",
        {"kind": "class"},
    )
    assert len(results) == 2
    ids = [r["n.id"] for r in results]
    assert ids == ["cls:bar", "cls:foo"]


# ---------------------------------------------------------------------------
# 31. close
# ---------------------------------------------------------------------------
def test_close(backend):
    backend.add_node(GraphNode(id="a", kind=NodeKind.CLASS, label="A"))
    backend.close()  # should not raise


# ---------------------------------------------------------------------------
# 32. determinism
# ---------------------------------------------------------------------------
def test_determinism(tmp_path):
    """Two identical sequences of operations produce identical results."""
    results = []
    for i in range(2):
        db_path = str(tmp_path / f"det_{i}.kuzu")
        b = KuzuBackend(db_path)
        b.add_node(GraphNode(id="cls:foo", kind=NodeKind.CLASS, label="Foo", module="mod.a"))
        b.add_node(GraphNode(id="cls:bar", kind=NodeKind.CLASS, label="Bar", module="mod.a"))
        b.add_node(GraphNode(id="ep:api", kind=NodeKind.ENDPOINT, label="GET /api", module="mod.b"))
        b.add_edge(GraphEdge(source="cls:foo", target="cls:bar", kind=EdgeKind.EXTENDS))
        b.add_edge(GraphEdge(source="ep:api", target="cls:foo", kind=EdgeKind.CALLS))

        node_ids = sorted(n.id for n in b.all_nodes())
        edge_tuples = sorted((e.source, e.target, e.kind.value) for e in b.all_edges())
        results.append((b.node_count, b.edge_count, node_ids, edge_tuples))
        b.close()

    assert results[0] == results[1]


# ---------------------------------------------------------------------------
# 33. persistence
# ---------------------------------------------------------------------------
def test_persistence(tmp_path):
    """Data survives close and reopen."""
    db_path = str(tmp_path / "persist.kuzu")
    b = KuzuBackend(db_path)
    b.add_node(GraphNode(id="cls:x", kind=NodeKind.CLASS, label="X"))
    b.add_node(GraphNode(id="cls:y", kind=NodeKind.CLASS, label="Y"))
    b.add_edge(GraphEdge(source="cls:x", target="cls:y", kind=EdgeKind.EXTENDS))
    b.close()

    b2 = KuzuBackend(db_path)
    assert b2.node_count == 2
    assert b2.edge_count == 1
    assert b2.has_node("cls:x")
    assert b2.has_node("cls:y")
    edges = b2.get_edges_between("cls:x", "cls:y")
    assert len(edges) == 1
    assert edges[0].kind == EdgeKind.EXTENDS
    b2.close()


# ---------------------------------------------------------------------------
# Extra: bulk operations
# ---------------------------------------------------------------------------
def test_bulk_add_nodes(backend):
    nodes = [
        GraphNode(id=f"cls:{i}", kind=NodeKind.CLASS, label=f"C{i}")
        for i in range(10)
    ]
    backend.bulk_add_nodes(nodes)
    assert backend.node_count == 10


def test_bulk_add_edges(backend):
    nodes = [
        GraphNode(id=f"cls:{i}", kind=NodeKind.CLASS, label=f"C{i}")
        for i in range(5)
    ]
    backend.bulk_add_nodes(nodes)
    edges = [
        GraphEdge(source=f"cls:{i}", target=f"cls:{i+1}", kind=EdgeKind.EXTENDS)
        for i in range(4)
    ]
    backend.bulk_add_edges(edges)
    assert backend.edge_count == 4


def test_bulk_add_nodes_deduplicates(backend):
    nodes = [
        GraphNode(id="cls:x", kind=NodeKind.CLASS, label="X"),
        GraphNode(id="cls:x", kind=NodeKind.CLASS, label="X_dup"),
        GraphNode(id="cls:y", kind=NodeKind.CLASS, label="Y"),
    ]
    backend.bulk_add_nodes(nodes)
    assert backend.node_count == 2


# ---------------------------------------------------------------------------
# Extra: edge with properties
# ---------------------------------------------------------------------------
def test_edge_properties(backend):
    backend.add_node(GraphNode(id="a", kind=NodeKind.CLASS, label="A"))
    backend.add_node(GraphNode(id="b", kind=NodeKind.CLASS, label="B"))
    backend.add_edge(GraphEdge(source="a", target="b", kind=EdgeKind.CALLS,
                               label="call_label", properties={"weight": 5}))
    edges = backend.get_edges_between("a", "b")
    assert len(edges) == 1
    assert edges[0].label == "call_label"
    assert edges[0].properties == {"weight": 5}


# ---------------------------------------------------------------------------
# Extra: node with annotations
# ---------------------------------------------------------------------------
def test_node_annotations(backend):
    backend.add_node(GraphNode(id="cls:x", kind=NodeKind.CLASS, label="X",
                               annotations=["@Entity", "@Table"]))
    node = backend.get_node("cls:x")
    assert node is not None
    assert node.annotations == ["@Entity", "@Table"]


# ---------------------------------------------------------------------------
# Extra: node with fqn
# ---------------------------------------------------------------------------
def test_node_fqn(backend):
    backend.add_node(GraphNode(id="cls:x", kind=NodeKind.CLASS, label="X",
                               fqn="com.example.X"))
    node = backend.get_node("cls:x")
    assert node is not None
    assert node.fqn == "com.example.X"


# ---------------------------------------------------------------------------
# Extra: node with no location returns None
# ---------------------------------------------------------------------------
def test_node_no_location(backend):
    backend.add_node(GraphNode(id="cls:x", kind=NodeKind.CLASS, label="X"))
    node = backend.get_node("cls:x")
    assert node is not None
    assert node.location is None


# ---------------------------------------------------------------------------
# Extra: update_node_properties on nonexistent node
# ---------------------------------------------------------------------------
def test_update_node_properties_nonexistent(backend):
    # Should not raise
    backend.update_node_properties("nonexistent", {"key": "val"})
