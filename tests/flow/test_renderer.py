"""Tests for flow renderers."""

from code_intelligence.flow.models import FlowDiagram, FlowEdge, FlowNode, FlowSubgraph
from code_intelligence.flow.renderer import render_html, render_json, render_mermaid


def _sample_diagram():
    sg = FlowSubgraph(id="ci", label="CI Pipeline", drill_down_view="ci", nodes=[
        FlowNode(id="build", label="Build Job", kind="job"),
        FlowNode(id="test", label="Test Job", kind="job"),
    ])
    return FlowDiagram(
        title="Test", view="overview",
        subgraphs=[sg],
        edges=[FlowEdge(source="build", target="test", label="needs")],
        stats={"jobs": 2},
    )


def test_render_mermaid_basic():
    d = _sample_diagram()
    mmd = render_mermaid(d)
    assert "graph LR" in mmd
    assert "subgraph" in mmd
    assert "build" in mmd
    assert "test" in mmd
    assert "needs" in mmd


def test_render_mermaid_empty():
    d = FlowDiagram(title="Empty", view="overview")
    mmd = render_mermaid(d)
    assert "graph LR" in mmd


def test_render_mermaid_determinism():
    d = _sample_diagram()
    assert render_mermaid(d) == render_mermaid(d)


def test_render_mermaid_styles():
    sg = FlowSubgraph(id="auth", label="Auth", nodes=[
        FlowNode(id="ok", label="Protected", kind="endpoint", style="success"),
        FlowNode(id="bad", label="Unprotected", kind="endpoint", style="danger"),
    ])
    d = FlowDiagram(title="T", view="auth", subgraphs=[sg])
    mmd = render_mermaid(d)
    assert ":::success" in mmd
    assert ":::danger" in mmd


def test_render_json():
    d = _sample_diagram()
    j = render_json(d)
    import json
    data = json.loads(j)
    assert data["title"] == "Test"
    assert data["view"] == "overview"
    assert len(data["subgraphs"]) == 1


def test_render_json_determinism():
    d = _sample_diagram()
    assert render_json(d) == render_json(d)


def test_render_html():
    views = {"overview": _sample_diagram()}
    html = render_html(views, {"total_nodes": 100, "total_edges": 200})
    assert "<!DOCTYPE html>" in html
    assert "Code IQ" in html
    assert "VIEWS_MERMAID" in html or "graph LR" in html
    assert "100" in html


def test_render_html_contains_all_views():
    views = {
        "overview": FlowDiagram(title="Overview", view="overview"),
        "ci": FlowDiagram(title="CI", view="ci"),
        "deploy": FlowDiagram(title="Deploy", view="deploy"),
    }
    html = render_html(views, {"total_nodes": 50})
    assert "overview" in html
    assert "ci" in html
    assert "deploy" in html
