"""Tests for graph models."""

import json

from code_intelligence.models.graph import (
    CodeGraph,
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)


def test_node_serialization():
    node = GraphNode(
        id="endpoint:order-service:GET:/api/orders",
        kind=NodeKind.ENDPOINT,
        label="GET /api/orders",
        fqn="com.example.OrderController.listOrders",
        module="module:order-service",
        location=SourceLocation(file_path="OrderController.java", line_start=12, line_end=15),
        annotations=["@GetMapping"],
        properties={"protocol": "REST", "http_method": "GET", "path_pattern": "/api/orders"},
    )
    data = node.model_dump()
    assert data["id"] == "endpoint:order-service:GET:/api/orders"
    assert data["kind"] == "endpoint"
    assert data["location"]["line_start"] == 12

    # Round-trip
    restored = GraphNode.model_validate(data)
    assert restored.id == node.id
    assert restored.kind == NodeKind.ENDPOINT


def test_edge_serialization():
    edge = GraphEdge(
        source="class:OrderController",
        target="endpoint:GET:/api/orders",
        kind=EdgeKind.EXPOSES,
        label="listOrders",
    )
    data = edge.model_dump()
    assert data["kind"] == "exposes"

    restored = GraphEdge.model_validate(data)
    assert restored.kind == EdgeKind.EXPOSES


def test_code_graph_json():
    graph = CodeGraph(
        version="1.0.0",
        metadata={"source_root": "/path/to/project"},
        nodes=[
            GraphNode(id="A", kind=NodeKind.CLASS, label="ClassA"),
            GraphNode(id="B", kind=NodeKind.CLASS, label="ClassB"),
        ],
        edges=[
            GraphEdge(source="A", target="B", kind=EdgeKind.CALLS),
        ],
    )
    json_str = graph.model_dump_json(indent=2)
    parsed = json.loads(json_str)
    assert parsed["version"] == "1.0.0"
    assert len(parsed["nodes"]) == 2
    assert len(parsed["edges"]) == 1


def test_all_node_kinds():
    """Verify all NodeKind values are valid."""
    for kind in NodeKind:
        node = GraphNode(id=f"test:{kind.value}", kind=kind, label=kind.value)
        assert node.kind == kind


def test_all_edge_kinds():
    """Verify all EdgeKind values are valid."""
    for kind in EdgeKind:
        edge = GraphEdge(source="A", target="B", kind=kind)
        assert edge.kind == kind
