"""Tests for GraphBuilder and linkers."""

from code_intelligence.detectors.base import DetectorResult
from code_intelligence.graph.builder import GraphBuilder
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
)


def test_merge_detector_result():
    builder = GraphBuilder()
    result = DetectorResult(
        nodes=[
            GraphNode(id="class:Foo", kind=NodeKind.CLASS, label="Foo"),
            GraphNode(id="class:Bar", kind=NodeKind.CLASS, label="Bar"),
        ],
        edges=[
            GraphEdge(source="class:Foo", target="class:Bar", kind=EdgeKind.CALLS),
        ],
    )
    builder.merge_detector_result(result)
    store = builder.build()
    assert store.node_count == 2
    assert store.edge_count == 1


def test_topic_linker():
    builder = GraphBuilder()
    builder.add_nodes([
        GraphNode(id="topic:events", kind=NodeKind.TOPIC, label="events"),
        GraphNode(id="method:producer", kind=NodeKind.METHOD, label="producer"),
        GraphNode(id="method:consumer", kind=NodeKind.METHOD, label="consumer"),
    ])
    builder.add_edges([
        GraphEdge(source="method:producer", target="topic:events", kind=EdgeKind.PRODUCES),
        GraphEdge(source="method:consumer", target="topic:events", kind=EdgeKind.CONSUMES),
    ])
    builder.run_linkers()
    store = builder.build()
    assert store.node_count >= 3
    assert store.edge_count >= 2


def test_module_containment_linker():
    builder = GraphBuilder()
    builder.add_nodes([
        GraphNode(id="class:Foo", kind=NodeKind.CLASS, label="Foo", module="module:core"),
        GraphNode(id="class:Bar", kind=NodeKind.CLASS, label="Bar", module="module:core"),
    ])
    builder.run_linkers()
    store = builder.build()
    # Should have created a MODULE node and CONTAINS edges
    modules = store.nodes_by_kind(NodeKind.MODULE)
    assert len(modules) >= 1
