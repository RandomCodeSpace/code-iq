"""Tests for MCP server tool functions."""

from __future__ import annotations

import json

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
from osscodeiq.server.mcp_server import (
    set_service,
    get_stats,
    query_nodes,
    query_edges,
    get_node_neighbors,
    get_ego_graph,
    find_cycles,
    find_shortest_path,
    find_consumers,
    find_producers,
    find_callers,
    find_dependencies,
    find_dependents,
    generate_flow,
    run_cypher,
    find_component_by_file,
    trace_impact,
    find_related_endpoints,
    search_graph,
    read_file,
    analyze_codebase,
)


@pytest.fixture(autouse=True)
def setup_service(tmp_path):
    """Set up a CodeIQService with test data for all MCP tool tests."""
    svc = CodeIQService(path=tmp_path, backend="networkx")
    store = GraphStore()
    store.add_node(
        GraphNode(
            id="ep:api:get",
            kind=NodeKind.ENDPOINT,
            label="GET /api/users",
            module="api.routes",
            location=SourceLocation(file_path="src/api.py", line_start=1, line_end=10),
        )
    )
    store.add_node(
        GraphNode(
            id="ent:user",
            kind=NodeKind.ENTITY,
            label="User",
            module="models",
            location=SourceLocation(file_path="src/models.py", line_start=1, line_end=20),
        )
    )
    store.add_node(
        GraphNode(
            id="cls:service",
            kind=NodeKind.CLASS,
            label="UserService",
            module="services",
            location=SourceLocation(file_path="src/service.py", line_start=1, line_end=30),
        )
    )
    store.add_edge(GraphEdge(source="ep:api:get", target="ent:user", kind=EdgeKind.QUERIES))
    store.add_edge(GraphEdge(source="ep:api:get", target="cls:service", kind=EdgeKind.CALLS))
    store.add_edge(GraphEdge(source="cls:service", target="ent:user", kind=EdgeKind.DEPENDS_ON))
    svc._store = store

    # Create a file that read_file can return
    (tmp_path / "src").mkdir(parents=True, exist_ok=True)
    (tmp_path / "src" / "api.py").write_text("# api module\ndef get_users(): pass\n")

    set_service(svc)
    yield svc
    set_service(None)


def test_get_stats():
    result = json.loads(get_stats())
    assert isinstance(result, dict)
    assert "backend" in result


def test_query_nodes_all():
    result = json.loads(query_nodes())
    assert isinstance(result, list)
    assert len(result) == 3


def test_query_nodes_filtered():
    result = json.loads(query_nodes(kind="endpoint"))
    assert isinstance(result, list)
    assert len(result) == 1
    assert result[0]["kind"] == "endpoint"


def test_query_edges_all():
    result = json.loads(query_edges())
    assert isinstance(result, list)
    assert len(result) == 3


def test_query_edges_filtered():
    result = json.loads(query_edges(kind="queries"))
    assert isinstance(result, list)
    assert len(result) == 1


def test_get_node_neighbors():
    result = json.loads(get_node_neighbors("ep:api:get"))
    assert isinstance(result, list)
    assert len(result) >= 1


def test_get_node_neighbors_direction():
    result = json.loads(get_node_neighbors("ent:user", direction="in"))
    assert isinstance(result, list)


def test_get_ego_graph():
    result = json.loads(get_ego_graph("ep:api:get", radius=1))
    assert isinstance(result, dict)
    assert "nodes" in result
    assert "edges" in result


def test_find_cycles():
    result = json.loads(find_cycles())
    assert isinstance(result, list)


def test_find_shortest_path_exists():
    result = json.loads(find_shortest_path("ep:api:get", "ent:user"))
    assert isinstance(result, list)
    assert "ep:api:get" in result
    assert "ent:user" in result


def test_find_shortest_path_no_path():
    result = json.loads(find_shortest_path("ent:user", "nonexistent:node"))
    assert isinstance(result, dict)
    assert "error" in result


def test_find_consumers():
    result = json.loads(find_consumers("ent:user"))
    assert isinstance(result, dict)
    assert "nodes" in result


def test_find_producers():
    result = json.loads(find_producers("ent:user"))
    assert isinstance(result, dict)
    assert "nodes" in result


def test_find_callers():
    result = json.loads(find_callers("cls:service"))
    assert isinstance(result, dict)
    assert "nodes" in result


def test_find_dependencies():
    result = json.loads(find_dependencies("cls:service"))
    assert isinstance(result, dict)


def test_find_dependents():
    result = json.loads(find_dependents("ent:user"))
    assert isinstance(result, dict)


def test_generate_flow():
    result = json.loads(generate_flow())
    assert isinstance(result, dict)


def test_generate_flow_mermaid():
    result = generate_flow(format="mermaid")
    # mermaid format returns a string (possibly JSON-wrapped)
    assert isinstance(result, str)


def test_run_cypher_error():
    """NetworkX backend does not support Cypher, expect error message."""
    result = json.loads(run_cypher("MATCH (n) RETURN n"))
    assert "error" in result


def test_find_component_by_file():
    result = json.loads(find_component_by_file("src/api.py"))
    assert isinstance(result, dict)
    assert result["file"] == "src/api.py"
    assert "components" in result
    assert len(result["components"]) >= 1


def test_trace_impact():
    result = json.loads(trace_impact("ep:api:get", depth=2))
    assert isinstance(result, dict)
    assert result["root"] == "ep:api:get"
    assert "impacted" in result
    assert "edges" in result


def test_find_related_endpoints():
    result = json.loads(find_related_endpoints("User"))
    assert isinstance(result, list)


def test_search_graph():
    result = json.loads(search_graph("user"))
    assert isinstance(result, list)
    assert len(result) >= 1


def test_search_graph_no_match():
    result = json.loads(search_graph("zzz_nonexistent_zzz"))
    assert isinstance(result, list)
    assert len(result) == 0


def test_read_file():
    result = read_file("src/api.py")
    assert "api module" in result


def test_read_file_not_found():
    result = read_file("nonexistent.py")
    assert "Error" in result


def test_analyze_codebase(setup_service, tmp_path):
    """Test analyze_codebase tool triggers analysis."""
    # Write a simple Python file so analysis has something to find
    (tmp_path / "hello.py").write_text("class Foo:\n    pass\n")
    result = json.loads(analyze_codebase(incremental=False))
    assert isinstance(result, dict)
