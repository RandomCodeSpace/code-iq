"""Tests for FlowEngine."""

from code_intelligence.flow.engine import AVAILABLE_VIEWS, FlowEngine
from code_intelligence.flow.models import FlowDiagram
from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import GraphEdge, GraphNode, EdgeKind, NodeKind


def _populated_store():
    store = GraphStore()
    store.add_node(GraphNode(id="ep1", kind=NodeKind.ENDPOINT, label="GET /users"))
    store.add_node(GraphNode(id="ent1", kind=NodeKind.ENTITY, label="User"))
    store.add_node(GraphNode(id="g1", kind=NodeKind.GUARD, label="JwtGuard", properties={"auth_type": "jwt"}))
    store.add_edge(GraphEdge(source="ep1", target="ent1", kind=EdgeKind.QUERIES))
    store.add_edge(GraphEdge(source="g1", target="ep1", kind=EdgeKind.PROTECTS))
    return store


def test_generate_overview():
    engine = FlowEngine(_populated_store())
    d = engine.generate("overview")
    assert isinstance(d, FlowDiagram)
    assert d.view == "overview"


def test_generate_all_views():
    engine = FlowEngine(_populated_store())
    all_views = engine.generate_all()
    assert set(all_views.keys()) == set(AVAILABLE_VIEWS)
    for name, diagram in all_views.items():
        assert diagram.view == name


def test_generate_invalid_view():
    engine = FlowEngine(GraphStore())
    try:
        engine.generate("nonexistent")
        assert False, "Should have raised ValueError"
    except ValueError as e:
        assert "nonexistent" in str(e)


def test_render_mermaid():
    engine = FlowEngine(_populated_store())
    d = engine.generate("overview")
    mmd = engine.render(d, "mermaid")
    assert "graph" in mmd
    assert isinstance(mmd, str)


def test_render_json():
    engine = FlowEngine(_populated_store())
    d = engine.generate("overview")
    j = engine.render(d, "json")
    import json
    data = json.loads(j)
    assert data["view"] == "overview"


def test_render_invalid_format():
    engine = FlowEngine(GraphStore())
    d = engine.generate("overview")
    try:
        engine.render(d, "invalid")
        assert False
    except ValueError:
        pass


def test_render_interactive():
    engine = FlowEngine(_populated_store())
    html = engine.render_interactive()
    assert "<!DOCTYPE html>" in html
    assert "overview" in html
    assert len(html) > 500


def test_output_consistency():
    """Same engine, same store — mermaid and json must describe the same diagram."""
    engine = FlowEngine(_populated_store())
    d = engine.generate("auth")
    mmd = engine.render(d, "mermaid")
    j = engine.render(d, "json")
    import json
    data = json.loads(j)
    # Both should have the same view name
    assert data["view"] == "auth"
    assert "auth" in mmd.lower() or "Auth" in mmd


def test_determinism():
    engine = FlowEngine(_populated_store())
    d1 = engine.generate("overview")
    d2 = engine.generate("overview")
    assert d1.to_dict() == d2.to_dict()
    assert engine.render(d1, "mermaid") == engine.render(d2, "mermaid")
