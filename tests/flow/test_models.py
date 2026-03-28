"""Tests for flow diagram models."""

from code_intelligence.flow.models import FlowDiagram, FlowEdge, FlowNode, FlowSubgraph


def test_flow_node_creation():
    n = FlowNode(id="n1", label="Build", kind="job", properties={"stage": "build"})
    assert n.id == "n1"
    assert n.style == "default"


def test_flow_diagram_all_nodes():
    sg = FlowSubgraph(id="sg1", label="CI", nodes=[FlowNode(id="n1", label="A", kind="job")])
    d = FlowDiagram(title="Test", view="ci", subgraphs=[sg], loose_nodes=[FlowNode(id="n2", label="B", kind="trigger")])
    assert len(d.all_nodes()) == 2
    assert {n.id for n in d.all_nodes()} == {"n1", "n2"}


def test_flow_diagram_empty():
    d = FlowDiagram(title="Empty", view="overview")
    assert len(d.all_nodes()) == 0
    assert d.to_dict()["view"] == "overview"


def test_flow_diagram_to_dict():
    d = FlowDiagram(title="Test", view="overview", stats={"total": 100})
    data = d.to_dict()
    assert data["title"] == "Test"
    assert data["view"] == "overview"
    assert data["stats"]["total"] == 100
    assert isinstance(data["subgraphs"], list)
    assert isinstance(data["edges"], list)


def test_flow_diagram_to_dict_with_nodes():
    sg = FlowSubgraph(id="ci", label="CI", drill_down_view="ci", nodes=[
        FlowNode(id="j1", label="build", kind="job", properties={"stage": "build"}),
    ])
    d = FlowDiagram(title="T", view="overview", subgraphs=[sg], edges=[FlowEdge(source="ci", target="deploy")])
    data = d.to_dict()
    assert len(data["subgraphs"]) == 1
    assert data["subgraphs"][0]["nodes"][0]["label"] == "build"
    assert data["edges"][0]["source"] == "ci"


def test_to_dict_determinism():
    sg = FlowSubgraph(id="ci", label="CI", nodes=[FlowNode(id="j1", label="build", kind="job")])
    d = FlowDiagram(title="T", view="overview", subgraphs=[sg])
    assert d.to_dict() == d.to_dict()
