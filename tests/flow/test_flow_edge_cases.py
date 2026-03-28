"""Flow view edge case tests — degenerate graphs, boundary conditions."""

import pytest

from code_intelligence.flow.engine import FlowEngine, AVAILABLE_VIEWS
from code_intelligence.flow.models import FlowDiagram
from code_intelligence.flow.renderer import render_mermaid, render_json, render_html
from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import GraphNode, GraphEdge, NodeKind, EdgeKind


class TestEmptyGraph:
    """All views should handle an empty graph gracefully."""

    def test_overview_empty(self):
        d = FlowEngine(GraphStore()).generate("overview")
        assert isinstance(d, FlowDiagram)
        assert d.view == "overview"
        assert len(d.all_nodes()) == 0

    def test_all_views_empty(self):
        engine = FlowEngine(GraphStore())
        for view in AVAILABLE_VIEWS:
            d = engine.generate(view)
            assert isinstance(d, FlowDiagram)
            assert d.view == view

    def test_render_mermaid_empty(self):
        d = FlowEngine(GraphStore()).generate("overview")
        mmd = render_mermaid(d)
        assert "graph" in mmd
        assert isinstance(mmd, str)

    def test_render_json_empty(self):
        d = FlowEngine(GraphStore()).generate("overview")
        j = render_json(d)
        import json
        data = json.loads(j)
        assert data["view"] == "overview"

    def test_render_html_empty(self):
        html = FlowEngine(GraphStore()).render_interactive()
        assert "<!DOCTYPE html>" in html


class TestSingleNode:
    """Graph with exactly 1 node."""

    @pytest.fixture
    def single_endpoint_store(self):
        s = GraphStore()
        s.add_node(GraphNode(id="ep1", kind=NodeKind.ENDPOINT, label="GET /health"))
        return s

    def test_overview_single_endpoint(self, single_endpoint_store):
        d = FlowEngine(single_endpoint_store).generate("overview")
        assert len(d.all_nodes()) >= 1
        assert d.stats.get("endpoints", 0) == 1

    def test_auth_single_endpoint_unprotected(self, single_endpoint_store):
        d = FlowEngine(single_endpoint_store).generate("auth")
        # Should show 1 unprotected endpoint
        unprotected = [n for n in d.all_nodes() if n.style == "danger"]
        assert len(unprotected) >= 1

    def test_runtime_single_endpoint(self, single_endpoint_store):
        d = FlowEngine(single_endpoint_store).generate("runtime")
        assert isinstance(d, FlowDiagram)


class TestInfraOnly:
    """Graph with only infrastructure nodes (no app code)."""

    @pytest.fixture
    def infra_store(self):
        s = GraphStore()
        for i in range(5):
            s.add_node(GraphNode(id=f"k8s:default/deploy-{i}", kind=NodeKind.INFRA_RESOURCE,
                                 label=f"Deployment {i}", properties={"kind": "Deployment"}))
        s.add_node(GraphNode(id="k8s:default/svc-0", kind=NodeKind.INFRA_RESOURCE,
                             label="Service 0", properties={"kind": "Service"}))
        s.add_edge(GraphEdge(source="k8s:default/svc-0", target="k8s:default/deploy-0", kind=EdgeKind.CONNECTS_TO))
        return s

    def test_overview_infra_only(self, infra_store):
        d = FlowEngine(infra_store).generate("overview")
        # Should have infra subgraph, no app subgraph
        infra_sgs = [sg for sg in d.subgraphs if "infra" in sg.id.lower() or "deploy" in sg.label.lower()]
        assert len(infra_sgs) >= 1

    def test_deploy_view_infra(self, infra_store):
        d = FlowEngine(infra_store).generate("deploy")
        assert len(d.all_nodes()) >= 1

    def test_runtime_empty_for_infra(self, infra_store):
        d = FlowEngine(infra_store).generate("runtime")
        # Runtime view should still work (may be empty or minimal)
        assert isinstance(d, FlowDiagram)


class TestAuthOnly:
    """Graph with guards but no endpoints."""

    @pytest.fixture
    def guards_no_endpoints(self):
        s = GraphStore()
        s.add_node(GraphNode(id="g1", kind=NodeKind.GUARD, label="JwtGuard", properties={"auth_type": "jwt"}))
        s.add_node(GraphNode(id="g2", kind=NodeKind.GUARD, label="RoleGuard", properties={"auth_type": "rbac"}))
        return s

    def test_auth_view_guards_only(self, guards_no_endpoints):
        d = FlowEngine(guards_no_endpoints).generate("auth")
        guard_nodes = [n for n in d.all_nodes() if n.kind == "guard"]
        assert len(guard_nodes) >= 1
        # No endpoint subgraph since there are no endpoints
        assert d.stats.get("coverage_pct", 0) == 0


class TestCIOnly:
    """Graph with CI nodes but nothing else."""

    @pytest.fixture
    def ci_store(self):
        s = GraphStore()
        s.add_node(GraphNode(id="gha:ci.yml", kind=NodeKind.MODULE, label="CI Workflow"))
        s.add_node(GraphNode(id="gha:ci.yml:job:build", kind=NodeKind.METHOD, label="build"))
        s.add_node(GraphNode(id="gha:ci.yml:job:test", kind=NodeKind.METHOD, label="test"))
        s.add_edge(GraphEdge(source="gha:ci.yml", target="gha:ci.yml:job:build", kind=EdgeKind.CONTAINS))
        s.add_edge(GraphEdge(source="gha:ci.yml", target="gha:ci.yml:job:test", kind=EdgeKind.CONTAINS))
        s.add_edge(GraphEdge(source="gha:ci.yml:job:test", target="gha:ci.yml:job:build", kind=EdgeKind.DEPENDS_ON))
        return s

    def test_overview_ci_only(self, ci_store):
        d = FlowEngine(ci_store).generate("overview")
        ci_sgs = [sg for sg in d.subgraphs if sg.drill_down_view == "ci"]
        assert len(ci_sgs) >= 1

    def test_ci_view_shows_jobs(self, ci_store):
        d = FlowEngine(ci_store).generate("ci")
        assert len(d.all_nodes()) >= 2  # At least the 2 jobs


class TestLargeGraph:
    """Graph with thousands of nodes — flow views should still be small."""

    @pytest.fixture
    def large_store(self):
        s = GraphStore()
        for i in range(5000):
            s.add_node(GraphNode(id=f"method_{i}", kind=NodeKind.METHOD, label=f"method{i}"))
        for i in range(100):
            s.add_node(GraphNode(id=f"ep_{i}", kind=NodeKind.ENDPOINT, label=f"GET /api/{i}"))
            s.add_node(GraphNode(id=f"ent_{i}", kind=NodeKind.ENTITY, label=f"Entity{i}"))
        return s

    def test_overview_max_nodes(self, large_store):
        d = FlowEngine(large_store).generate("overview")
        assert len(d.all_nodes()) <= 30  # Views should collapse, not explode

    def test_runtime_max_nodes(self, large_store):
        d = FlowEngine(large_store).generate("runtime")
        assert len(d.all_nodes()) <= 30


class TestRenderEdgeCases:
    """Renderer edge cases."""

    def test_mermaid_special_chars_in_labels(self):
        s = GraphStore()
        s.add_node(GraphNode(id="n1", kind=NodeKind.ENDPOINT, label='GET /users?name="foo"&age=<30>'))
        d = FlowEngine(s).generate("overview")
        mmd = render_mermaid(d)
        assert "&" not in mmd or "&#" in mmd  # Should be escaped

    def test_html_with_all_views(self):
        s = GraphStore()
        s.add_node(GraphNode(id="ep1", kind=NodeKind.ENDPOINT, label="GET /"))
        s.add_node(GraphNode(id="g1", kind=NodeKind.GUARD, label="Auth"))
        s.add_edge(GraphEdge(source="g1", target="ep1", kind=EdgeKind.PROTECTS))
        html = FlowEngine(s).render_interactive()
        # Should contain data for all 5 views
        for view in AVAILABLE_VIEWS:
            assert view in html


class TestDeterminism:
    """All views must be deterministic."""

    def test_all_views_deterministic(self):
        s = GraphStore()
        for i in range(50):
            s.add_node(GraphNode(id=f"n{i}", kind=NodeKind.METHOD, label=f"m{i}"))
        for i in range(10):
            s.add_node(GraphNode(id=f"ep{i}", kind=NodeKind.ENDPOINT, label=f"E{i}"))
            s.add_node(GraphNode(id=f"g{i}", kind=NodeKind.GUARD, label=f"G{i}", properties={"auth_type": "jwt"}))

        engine = FlowEngine(s)
        for view in AVAILABLE_VIEWS:
            d1 = engine.generate(view)
            d2 = engine.generate(view)
            assert render_mermaid(d1) == render_mermaid(d2), f"Non-deterministic: {view}"
