"""Tests for flow view generators."""

from code_intelligence.flow.models import FlowDiagram
from code_intelligence.flow.views import (
    build_auth_view,
    build_ci_view,
    build_deploy_view,
    build_overview,
    build_runtime_view,
)
from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _empty_store() -> GraphStore:
    return GraphStore()


def _populated_store() -> GraphStore:
    """Build a representative graph with CI, infra, app, and security nodes."""
    store = GraphStore()
    loc = SourceLocation(file_path="dummy.py", line_start=1)

    # -- CI / CD --
    store.add_node(GraphNode(id="gha:ci-build", kind=NodeKind.MODULE, label="CI Build", location=loc))
    store.add_node(GraphNode(id="gha:ci-deploy", kind=NodeKind.MODULE, label="CI Deploy", location=loc))
    for i in range(3):
        store.add_node(GraphNode(
            id=f"gha:ci-build:job:build-{i}", kind=NodeKind.METHOD, label=f"build-{i}",
            module="gha:ci-build", location=loc, properties={"stage": "build"},
        ))
    store.add_node(GraphNode(
        id="gha:ci-deploy:job:deploy-prod", kind=NodeKind.METHOD, label="deploy-prod",
        module="gha:ci-deploy", location=loc, properties={"stage": "deploy"},
    ))
    store.add_node(GraphNode(id="gha:trigger:push", kind=NodeKind.CONFIG_KEY, label="on: push", location=loc))
    # Job dependency
    store.add_edge(GraphEdge(source="gha:ci-build:job:build-1", target="gha:ci-build:job:build-0", kind=EdgeKind.DEPENDS_ON))

    # -- Infrastructure --
    store.add_node(GraphNode(id="k8s:deployment:api", kind=NodeKind.INFRA_RESOURCE, label="api deployment", location=loc, properties={"kind": "Deployment", "namespace": "default"}))
    store.add_node(GraphNode(id="k8s:service:api", kind=NodeKind.INFRA_RESOURCE, label="api service", location=loc, properties={"kind": "Service"}))
    store.add_node(GraphNode(id="compose:web", kind=NodeKind.INFRA_RESOURCE, label="web", location=loc, properties={"image": "web:latest"}))
    store.add_node(GraphNode(id="tf:aws_s3_bucket:assets", kind=NodeKind.INFRA_RESOURCE, label="assets bucket", location=loc, properties={"provider": "aws"}))
    store.add_node(GraphNode(id="docker:backend", kind=NodeKind.INFRA_RESOURCE, label="backend image", location=loc))
    store.add_node(GraphNode(id="azure:func:timer", kind=NodeKind.AZURE_RESOURCE, label="timer function", location=loc))
    # Infra edges
    store.add_edge(GraphEdge(source="k8s:deployment:api", target="k8s:service:api", kind=EdgeKind.CONNECTS_TO))

    # -- Application --
    for i in range(5):
        store.add_node(GraphNode(
            id=f"endpoint:/api/v1/resource-{i}", kind=NodeKind.ENDPOINT, label=f"GET /api/v1/resource-{i}",
            location=loc, properties={"layer": "backend"},
        ))
    store.add_node(GraphNode(id="endpoint:/home", kind=NodeKind.ENDPOINT, label="GET /home", location=loc, properties={"layer": "frontend"}))
    for i in range(3):
        store.add_node(GraphNode(id=f"entity:User{i}", kind=NodeKind.ENTITY, label=f"User{i}", location=loc))
    store.add_node(GraphNode(id="component:Header", kind=NodeKind.COMPONENT, label="Header", location=loc))
    store.add_node(GraphNode(id="component:Footer", kind=NodeKind.COMPONENT, label="Footer", location=loc))
    store.add_node(GraphNode(id="topic:orders", kind=NodeKind.TOPIC, label="orders topic", location=loc))
    store.add_node(GraphNode(id="queue:emails", kind=NodeKind.QUEUE, label="email queue", location=loc))
    store.add_node(GraphNode(id="db:postgres-main", kind=NodeKind.DATABASE_CONNECTION, label="postgres-main", location=loc))

    # -- Security --
    store.add_node(GraphNode(id="guard:jwt", kind=NodeKind.GUARD, label="JWTGuard", location=loc, properties={"auth_type": "jwt"}))
    store.add_node(GraphNode(id="guard:rbac", kind=NodeKind.GUARD, label="RBACGuard", location=loc, properties={"auth_type": "rbac"}))
    store.add_node(GraphNode(id="middleware:cors", kind=NodeKind.MIDDLEWARE, label="CORS", location=loc))
    # Protects edges (3 of 6 endpoints protected)
    for i in range(3):
        store.add_edge(GraphEdge(source="guard:jwt", target=f"endpoint:/api/v1/resource-{i}", kind=EdgeKind.PROTECTS))

    # -- Some generic code nodes --
    store.add_node(GraphNode(id="class:UserService", kind=NodeKind.CLASS, label="UserService", location=loc))
    store.add_node(GraphNode(id="method:UserService.get", kind=NodeKind.METHOD, label="get", location=loc))

    return store


# ---------------------------------------------------------------------------
# Empty store tests — no view should crash
# ---------------------------------------------------------------------------

class TestEmptyStore:
    def test_overview_empty(self):
        d = build_overview(_empty_store())
        assert isinstance(d, FlowDiagram)
        assert d.view == "overview"
        assert len(d.subgraphs) == 0
        assert len(d.all_nodes()) == 0

    def test_ci_empty(self):
        d = build_ci_view(_empty_store())
        assert d.view == "ci"
        assert len(d.subgraphs) == 0

    def test_deploy_empty(self):
        d = build_deploy_view(_empty_store())
        assert d.view == "deploy"
        assert len(d.subgraphs) == 0

    def test_runtime_empty(self):
        d = build_runtime_view(_empty_store())
        assert d.view == "runtime"
        assert len(d.subgraphs) == 0

    def test_auth_empty(self):
        d = build_auth_view(_empty_store())
        assert d.view == "auth"
        assert len(d.subgraphs) == 0
        assert d.stats["coverage_pct"] == 0


# ---------------------------------------------------------------------------
# Populated store — view names and basic structure
# ---------------------------------------------------------------------------

class TestViewNames:
    def test_overview_view_name(self):
        assert build_overview(_populated_store()).view == "overview"

    def test_ci_view_name(self):
        assert build_ci_view(_populated_store()).view == "ci"

    def test_deploy_view_name(self):
        assert build_deploy_view(_populated_store()).view == "deploy"

    def test_runtime_view_name(self):
        assert build_runtime_view(_populated_store()).view == "runtime"

    def test_auth_view_name(self):
        assert build_auth_view(_populated_store()).view == "auth"


# ---------------------------------------------------------------------------
# Overview tests
# ---------------------------------------------------------------------------

class TestOverview:
    def test_has_subgraphs(self):
        d = build_overview(_populated_store())
        sg_ids = {sg.id for sg in d.subgraphs}
        assert "ci" in sg_ids
        assert "infra" in sg_ids
        assert "app" in sg_ids
        assert "security" in sg_ids

    def test_node_count_bounded(self):
        d = build_overview(_populated_store())
        assert len(d.all_nodes()) <= 15

    def test_stats_populated(self):
        d = build_overview(_populated_store())
        assert d.stats["total_nodes"] > 0
        assert d.stats["total_edges"] > 0
        assert d.stats["endpoints"] == 6
        assert d.stats["entities"] == 3
        assert d.stats["guards"] == 2
        assert d.stats["components"] == 2

    def test_edges_present(self):
        d = build_overview(_populated_store())
        assert len(d.edges) > 0
        # CI -> infra deploy edge
        deploy_edges = [e for e in d.edges if e.label == "deploys"]
        assert len(deploy_edges) >= 1

    def test_drill_down_views(self):
        d = build_overview(_populated_store())
        drill_downs = {sg.drill_down_view for sg in d.subgraphs if sg.drill_down_view}
        assert "ci" in drill_downs
        assert "deploy" in drill_downs
        assert "runtime" in drill_downs
        assert "auth" in drill_downs


# ---------------------------------------------------------------------------
# CI view tests
# ---------------------------------------------------------------------------

class TestCIView:
    def test_has_workflow_subgraphs(self):
        d = build_ci_view(_populated_store())
        assert len(d.subgraphs) >= 2  # at least triggers + 1 workflow
        labels = {sg.label for sg in d.subgraphs}
        assert "Triggers" in labels

    def test_jobs_present(self):
        d = build_ci_view(_populated_store())
        all_node_labels = [n.label for n in d.all_nodes()]
        assert "build-0" in all_node_labels
        assert "deploy-prod" in all_node_labels

    def test_dependency_edges(self):
        d = build_ci_view(_populated_store())
        needs_edges = [e for e in d.edges if e.label == "needs"]
        assert len(needs_edges) >= 1

    def test_stats(self):
        d = build_ci_view(_populated_store())
        assert d.stats["workflows"] == 2
        assert d.stats["jobs"] == 4
        assert d.stats["triggers"] == 1

    def test_direction_is_td(self):
        d = build_ci_view(_populated_store())
        assert d.direction == "TD"


# ---------------------------------------------------------------------------
# Deploy view tests
# ---------------------------------------------------------------------------

class TestDeployView:
    def test_has_technology_subgraphs(self):
        d = build_deploy_view(_populated_store())
        sg_ids = {sg.id for sg in d.subgraphs}
        assert "k8s" in sg_ids
        assert "compose" in sg_ids
        assert "terraform" in sg_ids
        assert "docker" in sg_ids

    def test_azure_in_other(self):
        d = build_deploy_view(_populated_store())
        other_sg = next((sg for sg in d.subgraphs if sg.id == "other_infra"), None)
        assert other_sg is not None
        assert len(other_sg.nodes) >= 1  # azure resource

    def test_infra_edges(self):
        d = build_deploy_view(_populated_store())
        # k8s deployment -> service edge
        assert len(d.edges) >= 1

    def test_stats(self):
        d = build_deploy_view(_populated_store())
        assert d.stats["k8s"] == 2
        assert d.stats["compose"] == 1
        assert d.stats["terraform"] == 1
        assert d.stats["docker"] == 1


# ---------------------------------------------------------------------------
# Runtime view tests
# ---------------------------------------------------------------------------

class TestRuntimeView:
    def test_has_layer_subgraphs(self):
        d = build_runtime_view(_populated_store())
        sg_ids = {sg.id for sg in d.subgraphs}
        assert "frontend" in sg_ids
        assert "backend" in sg_ids
        assert "data" in sg_ids

    def test_frontend_has_components_and_routes(self):
        d = build_runtime_view(_populated_store())
        fe_sg = next(sg for sg in d.subgraphs if sg.id == "frontend")
        node_ids = {n.id for n in fe_sg.nodes}
        assert "rt_fe_endpoints" in node_ids
        assert "rt_components" in node_ids

    def test_backend_has_endpoints_and_messaging(self):
        d = build_runtime_view(_populated_store())
        be_sg = next(sg for sg in d.subgraphs if sg.id == "backend")
        node_ids = {n.id for n in be_sg.nodes}
        assert "rt_be_endpoints" in node_ids
        assert "rt_messaging" in node_ids

    def test_data_layer(self):
        d = build_runtime_view(_populated_store())
        data_sg = next(sg for sg in d.subgraphs if sg.id == "data")
        node_ids = {n.id for n in data_sg.nodes}
        assert "rt_entities" in node_ids
        assert "rt_database" in node_ids

    def test_cross_layer_edges(self):
        d = build_runtime_view(_populated_store())
        edge_labels = {e.label for e in d.edges}
        assert "calls" in edge_labels
        assert "queries" in edge_labels

    def test_stats(self):
        d = build_runtime_view(_populated_store())
        assert d.stats["endpoints"] == 6
        assert d.stats["entities"] == 3
        assert d.stats["components"] == 2
        assert d.stats["topics"] == 2
        assert d.stats["db_connections"] == 1


# ---------------------------------------------------------------------------
# Auth view tests
# ---------------------------------------------------------------------------

class TestAuthView:
    def test_guards_grouped_by_type(self):
        d = build_auth_view(_populated_store())
        guards_sg = next(sg for sg in d.subgraphs if sg.id == "guards")
        node_ids = {n.id for n in guards_sg.nodes}
        assert "auth_jwt" in node_ids
        assert "auth_rbac" in node_ids
        assert "auth_middleware" in node_ids

    def test_endpoint_coverage(self):
        d = build_auth_view(_populated_store())
        ep_sg = next(sg for sg in d.subgraphs if sg.id == "endpoints")
        node_ids = {n.id for n in ep_sg.nodes}
        assert "ep_protected" in node_ids
        assert "ep_unprotected" in node_ids

    def test_protection_edges(self):
        d = build_auth_view(_populated_store())
        protects_edges = [e for e in d.edges if e.label == "protects"]
        # jwt, rbac, middleware all have a protects edge
        assert len(protects_edges) == 3

    def test_coverage_stats(self):
        d = build_auth_view(_populated_store())
        assert d.stats["guards"] == 2
        assert d.stats["middleware"] == 1
        assert d.stats["protected"] == 3
        assert d.stats["unprotected"] == 3
        assert d.stats["coverage_pct"] == 50.0

    def test_protected_style(self):
        d = build_auth_view(_populated_store())
        ep_sg = next(sg for sg in d.subgraphs if sg.id == "endpoints")
        protected_node = next(n for n in ep_sg.nodes if n.id == "ep_protected")
        unprotected_node = next(n for n in ep_sg.nodes if n.id == "ep_unprotected")
        assert protected_node.style == "success"
        assert unprotected_node.style == "danger"


# ---------------------------------------------------------------------------
# Determinism — two calls on same store produce identical output
# ---------------------------------------------------------------------------

class TestDeterminism:
    def test_overview_determinism(self):
        store = _populated_store()
        assert build_overview(store).to_dict() == build_overview(store).to_dict()

    def test_ci_determinism(self):
        store = _populated_store()
        assert build_ci_view(store).to_dict() == build_ci_view(store).to_dict()

    def test_deploy_determinism(self):
        store = _populated_store()
        assert build_deploy_view(store).to_dict() == build_deploy_view(store).to_dict()

    def test_runtime_determinism(self):
        store = _populated_store()
        assert build_runtime_view(store).to_dict() == build_runtime_view(store).to_dict()

    def test_auth_determinism(self):
        store = _populated_store()
        assert build_auth_view(store).to_dict() == build_auth_view(store).to_dict()


# ---------------------------------------------------------------------------
# to_dict round-trip sanity
# ---------------------------------------------------------------------------

class TestToDict:
    def test_overview_to_dict_keys(self):
        d = build_overview(_populated_store()).to_dict()
        assert set(d.keys()) == {"title", "view", "direction", "subgraphs", "loose_nodes", "edges", "stats"}

    def test_ci_to_dict_keys(self):
        d = build_ci_view(_populated_store()).to_dict()
        assert d["direction"] == "TD"

    def test_all_views_serializable(self):
        """All views produce dicts with only JSON-safe types."""
        import json
        store = _populated_store()
        for builder in (build_overview, build_ci_view, build_deploy_view, build_runtime_view, build_auth_view):
            d = builder(store)
            # Should not raise
            json.dumps(d.to_dict())
