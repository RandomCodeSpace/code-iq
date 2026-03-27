"""Tests for output serializers."""

import json

import yaml

from code_intelligence.models.graph import CodeGraph, EdgeKind, GraphEdge, GraphNode, NodeKind
from code_intelligence.output.serializers import JsonSerializer, YamlSerializer


def _sample_graph() -> CodeGraph:
    return CodeGraph(
        version="1.0.0",
        metadata={"source_root": "/test"},
        nodes=[
            GraphNode(id="module:A", kind=NodeKind.MODULE, label="module-a"),
            GraphNode(id="endpoint:GET:/api/users", kind=NodeKind.ENDPOINT, label="GET /api/users"),
            GraphNode(id="entity:User", kind=NodeKind.ENTITY, label="User"),
        ],
        edges=[
            GraphEdge(source="module:A", target="endpoint:GET:/api/users", kind=EdgeKind.CONTAINS),
            GraphEdge(source="endpoint:GET:/api/users", target="entity:User", kind=EdgeKind.QUERIES),
        ],
    )


class TestJsonSerializer:
    def test_serialize(self):
        s = JsonSerializer()
        result = s.serialize(_sample_graph())
        data = json.loads(result)
        assert data["version"] == "1.0.0"
        assert len(data["nodes"]) == 3
        assert len(data["edges"]) == 2

    def test_serialize_compact(self):
        s = JsonSerializer()
        result = s.serialize(_sample_graph(), pretty=False)
        assert "\n" not in result


class TestYamlSerializer:
    def test_serialize(self):
        s = YamlSerializer()
        result = s.serialize(_sample_graph())
        data = yaml.safe_load(result)
        assert data["version"] == "1.0.0"
        assert len(data["nodes"]) == 3
