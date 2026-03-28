"""Integration tests for REST API routes."""
from __future__ import annotations

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from osscodeiq.graph.store import GraphStore
from osscodeiq.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation
from osscodeiq.server.middleware import AuthMiddleware
from osscodeiq.server.routes import create_router
from osscodeiq.server.service import CodeIQService


@pytest.fixture
def client(tmp_path):
    """Create a test client with pre-populated graph."""
    service = CodeIQService(path=tmp_path, backend="networkx")

    store = GraphStore()
    store.add_node(GraphNode(
        id="ep:users:get", kind=NodeKind.ENDPOINT, label="GET /users",
        module="api", location=SourceLocation(file_path="src/routes.py", line_start=10),
    ))
    store.add_node(GraphNode(
        id="ep:users:post", kind=NodeKind.ENDPOINT, label="POST /users",
        module="api", location=SourceLocation(file_path="src/routes.py", line_start=20),
    ))
    store.add_node(GraphNode(
        id="ent:user", kind=NodeKind.ENTITY, label="User",
        module="models", location=SourceLocation(file_path="src/models.py", line_start=1),
    ))
    store.add_node(GraphNode(
        id="cls:svc", kind=NodeKind.CLASS, label="UserService",
        module="services", location=SourceLocation(file_path="src/service.py", line_start=5),
    ))
    store.add_node(GraphNode(
        id="guard:jwt", kind=NodeKind.GUARD, label="JWT Auth",
        properties={"auth_type": "jwt"},
    ))

    store.add_edge(GraphEdge(source="ep:users:get", target="ent:user", kind=EdgeKind.QUERIES))
    store.add_edge(GraphEdge(source="ep:users:get", target="cls:svc", kind=EdgeKind.CALLS))
    store.add_edge(GraphEdge(source="cls:svc", target="ent:user", kind=EdgeKind.QUERIES))
    store.add_edge(GraphEdge(source="guard:jwt", target="ep:users:get", kind=EdgeKind.PROTECTS))

    service._store = store

    # Create a test file for read_file endpoint
    (tmp_path / "src").mkdir(parents=True, exist_ok=True)
    (tmp_path / "src" / "routes.py").write_text("# routes\ndef get_users(): pass\n")

    app = FastAPI()
    app.add_middleware(AuthMiddleware)
    router = create_router(service)
    app.include_router(router)

    @app.get("/")
    async def welcome():
        return {"status": "ok"}

    return TestClient(app)


# ── Basic ────────────────────────────────────────────────────────────────────


def test_welcome(client):
    """GET / returns 200 with status ok."""
    resp = client.get("/")
    assert resp.status_code == 200
    assert resp.json()["status"] == "ok"


def test_stats(client):
    """GET /api/stats returns dict with backend key."""
    resp = client.get("/api/stats")
    assert resp.status_code == 200
    data = resp.json()
    assert "backend" in data
    assert data["backend"] == "networkx"


# ── Nodes ────────────────────────────────────────────────────────────────────


def test_list_nodes(client):
    """GET /api/nodes returns all 5 nodes."""
    resp = client.get("/api/nodes")
    assert resp.status_code == 200
    nodes = resp.json()
    assert len(nodes) == 5


def test_list_nodes_filter_kind(client):
    """GET /api/nodes?kind=endpoint returns 2 endpoint nodes."""
    resp = client.get("/api/nodes", params={"kind": "endpoint"})
    assert resp.status_code == 200
    nodes = resp.json()
    assert len(nodes) == 2
    assert all(n["kind"] == "endpoint" for n in nodes)


def test_list_nodes_pagination(client):
    """GET /api/nodes?limit=2&offset=2 returns 2 nodes from the middle."""
    resp = client.get("/api/nodes", params={"limit": 2, "offset": 2})
    assert resp.status_code == 200
    nodes = resp.json()
    assert len(nodes) == 2


def test_get_node(client):
    """GET /api/nodes/ent:user returns the User node."""
    resp = client.get("/api/nodes/ent:user")
    assert resp.status_code == 200
    node = resp.json()
    assert node["id"] == "ent:user"
    assert node["kind"] == "entity"
    assert node["label"] == "User"


def test_get_node_404(client):
    """GET /api/nodes/nonexistent returns 404."""
    resp = client.get("/api/nodes/nonexistent")
    assert resp.status_code == 404


# ── Edges ────────────────────────────────────────────────────────────────────


def test_list_edges(client):
    """GET /api/edges returns all 4 edges."""
    resp = client.get("/api/edges")
    assert resp.status_code == 200
    edges = resp.json()
    assert len(edges) == 4


def test_list_edges_filter(client):
    """GET /api/edges?kind=queries returns 2 QUERIES edges."""
    resp = client.get("/api/edges", params={"kind": "queries"})
    assert resp.status_code == 200
    edges = resp.json()
    assert len(edges) == 2
    assert all(e["kind"] == "queries" for e in edges)


# ── Neighbors & Ego ─────────────────────────────────────────────────────────


def test_get_neighbors(client):
    """GET /api/nodes/ep:users:get/neighbors returns connected nodes."""
    resp = client.get("/api/nodes/ep:users:get/neighbors")
    assert resp.status_code == 200
    neighbors = resp.json()
    assert len(neighbors) >= 2
    neighbor_ids = {n["id"] for n in neighbors}
    assert "ent:user" in neighbor_ids
    assert "cls:svc" in neighbor_ids


def test_get_ego(client):
    """GET /api/ego/ep:users:get?radius=1 returns subgraph dict."""
    resp = client.get("/api/ego/ep:users:get", params={"radius": 1})
    assert resp.status_code == 200
    data = resp.json()
    assert "nodes" in data
    assert "edges" in data
    assert len(data["nodes"]) >= 1


# ── Query endpoints ─────────────────────────────────────────────────────────


def test_find_cycles(client):
    """GET /api/query/cycles returns a list (possibly empty)."""
    resp = client.get("/api/query/cycles")
    assert resp.status_code == 200
    assert isinstance(resp.json(), list)


def test_shortest_path(client):
    """GET /api/query/shortest-path returns a path from ep:users:get to ent:user."""
    resp = client.get("/api/query/shortest-path", params={
        "source": "ep:users:get",
        "target": "ent:user",
    })
    assert resp.status_code == 200
    path = resp.json()
    assert isinstance(path, list)
    assert path[0] == "ep:users:get"
    assert path[-1] == "ent:user"


def test_shortest_path_404(client):
    """GET /api/query/shortest-path with unreachable target returns 404."""
    resp = client.get("/api/query/shortest-path", params={
        "source": "guard:jwt",
        "target": "nonexistent",
    })
    assert resp.status_code == 404


def test_consumers(client):
    """GET /api/query/consumers/ent:user returns a result dict."""
    resp = client.get("/api/query/consumers/ent:user")
    assert resp.status_code == 200
    data = resp.json()
    assert "nodes" in data
    assert "edges" in data


def test_producers(client):
    """GET /api/query/producers/ent:user returns a result dict."""
    resp = client.get("/api/query/producers/ent:user")
    assert resp.status_code == 200
    data = resp.json()
    assert "nodes" in data
    assert "edges" in data


def test_callers(client):
    """GET /api/query/callers/cls:svc returns a result dict."""
    resp = client.get("/api/query/callers/cls:svc")
    assert resp.status_code == 200
    data = resp.json()
    assert "nodes" in data
    assert "edges" in data


def test_dependencies(client):
    """GET /api/query/dependencies/api returns a result dict."""
    resp = client.get("/api/query/dependencies/api")
    assert resp.status_code == 200
    data = resp.json()
    assert "nodes" in data
    assert "edges" in data


def test_dependents(client):
    """GET /api/query/dependents/models returns a result dict."""
    resp = client.get("/api/query/dependents/models")
    assert resp.status_code == 200
    data = resp.json()
    assert "nodes" in data
    assert "edges" in data


# ── Flow ─────────────────────────────────────────────────────────────────────


def test_flow_overview(client):
    """GET /api/flow/overview returns a dict with title."""
    resp = client.get("/api/flow/overview")
    assert resp.status_code == 200
    data = resp.json()
    assert "title" in data


def test_flow_all(client):
    """GET /api/flow returns a dict with overview key."""
    resp = client.get("/api/flow")
    assert resp.status_code == 200
    data = resp.json()
    assert "overview" in data


# ── Cypher ───────────────────────────────────────────────────────────────────


def test_cypher_400(client):
    """POST /api/cypher returns 400 when backend is networkx."""
    resp = client.post("/api/cypher", json={"query": "MATCH (n) RETURN n"})
    assert resp.status_code == 400
    assert "Cypher" in resp.json()["detail"] or "cypher" in resp.json()["detail"].lower()


# ── Triage ───────────────────────────────────────────────────────────────────


def test_triage_component(client):
    """GET /api/triage/component?file_path=src/routes.py returns components."""
    resp = client.get("/api/triage/component", params={"file_path": "src/routes.py"})
    assert resp.status_code == 200
    data = resp.json()
    assert "file" in data
    assert "components" in data
    assert data["file"] == "src/routes.py"
    assert len(data["components"]) >= 1


def test_triage_impact(client):
    """GET /api/triage/impact/ep:users:get returns impact analysis."""
    resp = client.get("/api/triage/impact/ep:users:get")
    assert resp.status_code == 200
    data = resp.json()
    assert "root" in data
    assert data["root"] == "ep:users:get"
    assert "impacted" in data
    assert "edges" in data


def test_triage_endpoints(client):
    """GET /api/triage/endpoints?identifier=user returns matching endpoints."""
    resp = client.get("/api/triage/endpoints", params={"identifier": "user"})
    assert resp.status_code == 200
    endpoints = resp.json()
    assert isinstance(endpoints, list)
    # "user" matches several nodes; endpoints reachable within 3 hops
    assert any(ep["kind"] == "endpoint" for ep in endpoints)


# ── Search ───────────────────────────────────────────────────────────────────


def test_search(client):
    """GET /api/search?q=user returns matching nodes."""
    resp = client.get("/api/search", params={"q": "user"})
    assert resp.status_code == 200
    results = resp.json()
    assert len(results) >= 1
    # All results should contain "user" in some field
    for r in results:
        combined = (
            r["id"] + r["label"] + (r.get("fqn") or "") + (r.get("module") or "")
        ).lower()
        assert "user" in combined


def test_search_no_results(client):
    """GET /api/search?q=zzz returns empty list."""
    resp = client.get("/api/search", params={"q": "zzz"})
    assert resp.status_code == 200
    assert resp.json() == []


# ── File ─────────────────────────────────────────────────────────────────────


def test_file(client):
    """GET /api/file?path=src/routes.py returns file content."""
    resp = client.get("/api/file", params={"path": "src/routes.py"})
    assert resp.status_code == 200
    assert "# routes" in resp.text


def test_file_traversal(client):
    """GET /api/file?path=../../etc/passwd returns 400 for path traversal."""
    resp = client.get("/api/file", params={"path": "../../etc/passwd"})
    assert resp.status_code == 400
