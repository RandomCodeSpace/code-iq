"""Tests for CodeIQService."""

from __future__ import annotations

import pytest

from osscodeiq.graph.store import GraphStore
from osscodeiq.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)
from osscodeiq.server.service import CodeIQService


@pytest.fixture
def service(tmp_path):
    """Create a service with a pre-populated in-memory graph."""
    svc = CodeIQService(path=tmp_path, backend="networkx")
    store = GraphStore()
    store.add_node(GraphNode(
        id="ep:users:get", kind=NodeKind.ENDPOINT, label="GET /users",
        module="api.routes",
        location=SourceLocation(file_path="src/routes/users.py", line_start=10, line_end=20),
    ))
    store.add_node(GraphNode(
        id="ep:users:post", kind=NodeKind.ENDPOINT, label="POST /users",
        module="api.routes",
        location=SourceLocation(file_path="src/routes/users.py", line_start=25, line_end=35),
    ))
    store.add_node(GraphNode(
        id="ent:user", kind=NodeKind.ENTITY, label="User",
        module="models",
        location=SourceLocation(file_path="src/models/user.py", line_start=1, line_end=30),
    ))
    store.add_node(GraphNode(
        id="cls:userservice", kind=NodeKind.CLASS, label="UserService",
        module="services",
        location=SourceLocation(file_path="src/services/user_service.py", line_start=5, line_end=50),
    ))
    store.add_node(GraphNode(
        id="guard:jwt", kind=NodeKind.GUARD, label="JWT Auth",
        properties={"auth_type": "jwt"},
    ))
    store.add_edge(GraphEdge(source="ep:users:get", target="ent:user", kind=EdgeKind.QUERIES))
    store.add_edge(GraphEdge(source="ep:users:get", target="cls:userservice", kind=EdgeKind.CALLS))
    store.add_edge(GraphEdge(source="cls:userservice", target="ent:user", kind=EdgeKind.QUERIES))
    store.add_edge(GraphEdge(source="guard:jwt", target="ep:users:get", kind=EdgeKind.PROTECTS))
    store.add_edge(GraphEdge(source="guard:jwt", target="ep:users:post", kind=EdgeKind.PROTECTS))
    svc._store = store
    return svc


def test_get_stats(service):
    stats = service.get_stats()
    assert stats["total_nodes"] == 5
    assert stats["total_edges"] == 5
    assert stats["backend"] == "networkx"
    assert "node_counts_by_kind" in stats


def test_list_nodes_all(service):
    nodes = service.list_nodes()
    assert len(nodes) == 5
    # Deterministic ordering by id
    assert nodes[0]["id"] < nodes[1]["id"]


def test_list_nodes_by_kind(service):
    endpoints = service.list_nodes(kind="endpoint")
    assert len(endpoints) == 2
    assert all(n["kind"] == "endpoint" for n in endpoints)


def test_list_nodes_pagination(service):
    page1 = service.list_nodes(limit=2, offset=0)
    page2 = service.list_nodes(limit=2, offset=2)
    assert len(page1) == 2
    assert len(page2) == 2
    assert page1[0]["id"] != page2[0]["id"]


def test_list_edges_all(service):
    edges = service.list_edges()
    assert len(edges) == 5


def test_list_edges_by_kind(service):
    queries = service.list_edges(kind="queries")
    assert len(queries) == 2
    assert all(e["kind"] == "queries" for e in queries)


def test_get_node_found(service):
    node = service.get_node("ep:users:get")
    assert node is not None
    assert node["label"] == "GET /users"
    assert node["kind"] == "endpoint"
    assert node["location"]["file_path"] == "src/routes/users.py"


def test_get_node_not_found(service):
    assert service.get_node("nonexistent") is None


def test_get_neighbors(service):
    neighbors = service.get_neighbors("ep:users:get")
    assert len(neighbors) > 0
    neighbor_ids = [n["id"] for n in neighbors]
    assert "ent:user" in neighbor_ids
    assert "cls:userservice" in neighbor_ids


def test_get_ego(service):
    ego = service.get_ego("ep:users:get", radius=1)
    assert "nodes" in ego
    assert "edges" in ego
    assert len(ego["nodes"]) > 1


def test_find_cycles_empty(service):
    cycles = service.find_cycles()
    assert isinstance(cycles, list)


def test_shortest_path(service):
    path = service.shortest_path("ep:users:get", "ent:user")
    assert path is not None
    assert path[0] == "ep:users:get"
    assert path[-1] == "ent:user"


def test_shortest_path_not_found(service):
    path = service.shortest_path("guard:jwt", "nonexistent")
    assert path is None


def test_consumers_of(service):
    result = service.consumers_of("ent:user")
    assert "nodes" in result
    assert "edges" in result


def test_callers_of(service):
    result = service.callers_of("cls:userservice")
    assert "nodes" in result


def test_generate_flow(service):
    flow = service.generate_flow("overview", "json")
    assert isinstance(flow, dict)
    assert "title" in flow


def test_generate_all_flows(service):
    flows = service.generate_all_flows()
    assert "overview" in flows
    assert "ci" in flows
    assert "auth" in flows


def test_cypher_on_networkx_raises(service):
    with pytest.raises(ValueError, match="Cypher"):
        service.query_cypher("MATCH (n) RETURN n")


def test_search_graph(service):
    results = service.search_graph("user")
    assert len(results) > 0
    # Should find nodes with "user" in label or id
    assert any("user" in r["label"].lower() or "user" in r["id"].lower() for r in results)


def test_search_graph_no_results(service):
    results = service.search_graph("zzzznonexistent")
    assert results == []


def test_find_component_by_file(service):
    result = service.find_component_by_file("src/routes/users.py")
    assert "file" in result
    assert "components" in result
    assert len(result["components"]) > 0


def test_find_related_endpoints(service):
    endpoints = service.find_related_endpoints("user")
    assert len(endpoints) > 0
    assert all(ep["kind"] == "endpoint" for ep in endpoints)


def test_trace_impact(service):
    result = service.trace_impact("ep:users:get", depth=2)
    assert "root" in result
    assert "impacted" in result


def test_read_file(service, tmp_path):
    # Create a test file in the codebase path
    test_file = tmp_path / "hello.py"
    test_file.write_text("print('hello')")
    content = service.read_file("hello.py")
    assert content == "print('hello')"


def test_read_file_path_traversal(service):
    with pytest.raises(ValueError, match="outside"):
        service.read_file("../../etc/passwd")


# ── list_kinds ──────────────────────────────────────────────────────────────


def test_list_kinds(service):
    result = service.list_kinds()
    assert "kinds" in result
    assert "total_nodes" in result
    assert "total_edges" in result
    assert result["total_nodes"] == 5
    assert result["total_edges"] == 5
    # Sorted by count desc — endpoint has 2 nodes, should be first
    kinds = result["kinds"]
    assert len(kinds) >= 3  # endpoint, entity, class, guard
    assert kinds[0]["count"] >= kinds[-1]["count"]
    # Each kind has preview list of up to 5 labels
    for k in kinds:
        assert "kind" in k
        assert "count" in k
        assert "preview" in k
        assert len(k["preview"]) <= 5


def test_list_kinds_preview_content(service):
    result = service.list_kinds()
    endpoint_kind = next(k for k in result["kinds"] if k["kind"] == "endpoint")
    assert endpoint_kind["count"] == 2
    assert len(endpoint_kind["preview"]) == 2
    assert "GET /users" in endpoint_kind["preview"]
    assert "POST /users" in endpoint_kind["preview"]


def test_list_kinds_empty():
    """Empty graph returns zero counts."""
    from osscodeiq.server.service import CodeIQService
    import tempfile, pathlib
    with tempfile.TemporaryDirectory() as td:
        svc = CodeIQService(path=pathlib.Path(td), backend="networkx")
        svc._store = GraphStore()
        result = svc.list_kinds()
        assert result["kinds"] == []
        assert result["total_nodes"] == 0
        assert result["total_edges"] == 0


# ── nodes_by_kind_paginated ────────────────────────────────────────────────


def test_nodes_by_kind_paginated(service):
    result = service.nodes_by_kind_paginated("endpoint")
    assert result["kind"] == "endpoint"
    assert result["total"] == 2
    assert len(result["nodes"]) == 2
    for n in result["nodes"]:
        assert "id" in n
        assert "label" in n
        assert "module" in n
        assert "file_path" in n
        assert "line_start" in n
        assert "edge_count" in n
        assert "properties" in n


def test_nodes_by_kind_paginated_pagination(service):
    result = service.nodes_by_kind_paginated("endpoint", limit=1, offset=0)
    assert result["total"] == 2
    assert len(result["nodes"]) == 1
    first_id = result["nodes"][0]["id"]

    result2 = service.nodes_by_kind_paginated("endpoint", limit=1, offset=1)
    assert len(result2["nodes"]) == 1
    assert result2["nodes"][0]["id"] != first_id


def test_nodes_by_kind_paginated_invalid_kind(service):
    result = service.nodes_by_kind_paginated("nonexistent_kind_xyz")
    assert result["total"] == 0
    assert result["nodes"] == []


def test_nodes_by_kind_paginated_edge_count(service):
    result = service.nodes_by_kind_paginated("endpoint")
    # ep:users:get has 2 outgoing + 1 incoming (guard:jwt PROTECTS) = 3
    get_node = next(n for n in result["nodes"] if n["id"] == "ep:users:get")
    assert get_node["edge_count"] == 3


# ── node_detail_with_edges ─────────────────────────────────────────────────


def test_node_detail_with_edges(service):
    result = service.node_detail_with_edges("ep:users:get")
    assert result is not None
    assert result["node"]["id"] == "ep:users:get"
    assert result["node"]["kind"] == "endpoint"
    assert isinstance(result["edges_out"], list)
    assert isinstance(result["edges_in"], list)
    # ep:users:get -> ent:user (QUERIES), -> cls:userservice (CALLS)
    assert len(result["edges_out"]) == 2
    out_targets = {e["target_id"] for e in result["edges_out"]}
    assert "ent:user" in out_targets
    assert "cls:userservice" in out_targets
    # guard:jwt -> ep:users:get (PROTECTS)
    assert len(result["edges_in"]) == 1
    assert result["edges_in"][0]["source_id"] == "guard:jwt"


def test_node_detail_with_edges_not_found(service):
    result = service.node_detail_with_edges("nonexistent")
    assert result is None


def test_node_detail_with_edges_no_edges(service):
    """Node with no connections returns empty edge lists."""
    # guard:jwt has outgoing edges only
    result = service.node_detail_with_edges("ent:user")
    assert result is not None
    assert result["node"]["id"] == "ent:user"
    # ent:user has no outgoing edges
    assert len(result["edges_out"]) == 0
    # ent:user has 2 incoming edges (from ep:users:get and cls:userservice)
    assert len(result["edges_in"]) == 2


# ── Determinism ─────────────────────────────────────────────────────────────


def test_determinism(service):
    """Two calls produce identical output."""
    stats1 = service.get_stats()
    stats2 = service.get_stats()
    assert stats1 == stats2

    nodes1 = service.list_nodes()
    nodes2 = service.list_nodes()
    assert nodes1 == nodes2

    edges1 = service.list_edges()
    edges2 = service.list_edges()
    assert edges1 == edges2
