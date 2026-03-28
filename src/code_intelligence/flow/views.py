"""Flow view generators — each produces a small, clean FlowDiagram from the full graph."""

from __future__ import annotations

from code_intelligence.flow.models import FlowDiagram, FlowEdge, FlowNode, FlowSubgraph
from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import EdgeKind, NodeKind


def build_overview(store: GraphStore) -> FlowDiagram:
    """High-level overview with 4 subgraphs: CI, Infrastructure, Application, Security."""
    subgraphs = []
    edges = []

    # CI/CD subgraph
    ci_nodes = []
    workflows = [n for n in store.all_nodes() if n.kind == NodeKind.MODULE and any(
        p in n.id for p in ("gha:", "gitlab:")
    )]
    ci_jobs = [n for n in store.all_nodes() if n.kind == NodeKind.METHOD and any(
        p in n.id for p in ("gha:", "gitlab:")
    )]
    if workflows or ci_jobs:
        ci_nodes.append(FlowNode(id="ci_pipelines", label=f"Pipelines x{len(workflows)}", kind="pipeline",
                                 properties={"count": len(workflows)}))
        if ci_jobs:
            ci_nodes.append(FlowNode(id="ci_jobs", label=f"Jobs x{len(ci_jobs)}", kind="job",
                                     properties={"count": len(ci_jobs)}))
            edges.append(FlowEdge(source="ci_pipelines", target="ci_jobs"))
        subgraphs.append(FlowSubgraph(id="ci", label="CI/CD Pipeline", nodes=ci_nodes, drill_down_view="ci"))

    # Infrastructure subgraph
    infra_nodes_raw = store.nodes_by_kind(NodeKind.INFRA_RESOURCE) + store.nodes_by_kind(NodeKind.AZURE_RESOURCE)
    if infra_nodes_raw:
        # Group by type from properties or id prefix
        k8s = [n for n in infra_nodes_raw if "k8s:" in n.id]
        docker = [n for n in infra_nodes_raw if "compose:" in n.id or "dockerfile" in n.id.lower()]
        terraform = [n for n in infra_nodes_raw if "tf:" in n.id]
        other_infra = [n for n in infra_nodes_raw if n not in k8s and n not in docker and n not in terraform]

        infra_flow_nodes = []
        if k8s:
            infra_flow_nodes.append(FlowNode(id="infra_k8s", label=f"K8s Resources x{len(k8s)}", kind="k8s",
                                              properties={"count": len(k8s)}))
        if docker:
            infra_flow_nodes.append(FlowNode(id="infra_docker", label=f"Docker x{len(docker)}", kind="docker",
                                              properties={"count": len(docker)}))
        if terraform:
            infra_flow_nodes.append(FlowNode(id="infra_tf", label=f"Terraform x{len(terraform)}", kind="terraform",
                                              properties={"count": len(terraform)}))
        if other_infra:
            infra_flow_nodes.append(FlowNode(id="infra_other", label=f"Infra x{len(other_infra)}", kind="infra",
                                              properties={"count": len(other_infra)}))
        if infra_flow_nodes:
            subgraphs.append(FlowSubgraph(id="infra", label="Infrastructure", nodes=infra_flow_nodes, drill_down_view="deploy"))

    # Application subgraph
    endpoints = store.nodes_by_kind(NodeKind.ENDPOINT)
    entities = store.nodes_by_kind(NodeKind.ENTITY)
    classes = store.nodes_by_kind(NodeKind.CLASS)
    methods = store.nodes_by_kind(NodeKind.METHOD)
    # Exclude CI methods from method count
    app_methods = [m for m in methods if not any(p in m.id for p in ("gha:", "gitlab:"))]
    components = store.nodes_by_kind(NodeKind.COMPONENT)
    topics = store.nodes_by_kind(NodeKind.TOPIC) + store.nodes_by_kind(NodeKind.QUEUE)
    db_conns = store.nodes_by_kind(NodeKind.DATABASE_CONNECTION)

    app_nodes = []
    if endpoints:
        app_nodes.append(FlowNode(id="app_endpoints", label=f"Endpoints x{len(endpoints)}", kind="endpoint",
                                  properties={"count": len(endpoints)}))
    if entities:
        app_nodes.append(FlowNode(id="app_entities", label=f"Entities x{len(entities)}", kind="entity",
                                  properties={"count": len(entities)}))
    if components:
        app_nodes.append(FlowNode(id="app_components", label=f"Components x{len(components)}", kind="component",
                                  properties={"count": len(components)}))
    if topics:
        app_nodes.append(FlowNode(id="app_messaging", label=f"Topics/Queues x{len(topics)}", kind="messaging",
                                  properties={"count": len(topics)}))
    if db_conns:
        app_nodes.append(FlowNode(id="app_database", label=f"DB Connections x{len(db_conns)}", kind="database",
                                  properties={"count": len(db_conns)}))
    if not app_nodes and (classes or app_methods):
        app_nodes.append(FlowNode(id="app_code", label=f"Classes x{len(classes)}, Methods x{len(app_methods)}", kind="code",
                                  properties={"classes": len(classes), "methods": len(app_methods)}))
    if app_nodes:
        subgraphs.append(FlowSubgraph(id="app", label="Application", nodes=app_nodes, drill_down_view="runtime"))
        # Add internal edges
        if endpoints and entities:
            edges.append(FlowEdge(source="app_endpoints", target="app_entities", label="queries"))
        if endpoints and any(n.id == "app_messaging" for n in app_nodes):
            edges.append(FlowEdge(source="app_endpoints", target="app_messaging", style="dotted"))

    # Security subgraph
    guards = store.nodes_by_kind(NodeKind.GUARD)
    middleware = store.nodes_by_kind(NodeKind.MIDDLEWARE)
    if guards or middleware:
        sec_nodes = []
        if guards:
            sec_nodes.append(FlowNode(id="sec_guards", label=f"Auth Guards x{len(guards)}", kind="guard",
                                      properties={"count": len(guards)}))
        if middleware:
            sec_nodes.append(FlowNode(id="sec_middleware", label=f"Middleware x{len(middleware)}", kind="middleware",
                                      properties={"count": len(middleware)}))
        subgraphs.append(FlowSubgraph(id="security", label="Security", nodes=sec_nodes, drill_down_view="auth"))
        # Guards protect endpoints
        if guards and endpoints:
            edges.append(FlowEdge(source="sec_guards", target="app_endpoints", label="protects", style="thick"))

    # Cross-subgraph edges
    if ci_nodes and infra_nodes_raw:
        infra_flow_nodes_local = [sg for sg in subgraphs if sg.id == "infra"]
        if infra_flow_nodes_local and infra_flow_nodes_local[0].nodes:
            first_infra = infra_flow_nodes_local[0].nodes[0].id
            edges.append(FlowEdge(source="ci_jobs" if ci_jobs else "ci_pipelines", target=first_infra, label="deploys"))
    if infra_nodes_raw and app_nodes:
        infra_flow_nodes_local = [sg for sg in subgraphs if sg.id == "infra"]
        if infra_flow_nodes_local and infra_flow_nodes_local[0].nodes:
            first_infra = infra_flow_nodes_local[0].nodes[0].id
            edges.append(FlowEdge(source=first_infra, target=app_nodes[0].id, label="hosts"))

    stats = {
        "total_nodes": store.node_count,
        "total_edges": store.edge_count,
        "endpoints": len(endpoints),
        "entities": len(entities),
        "guards": len(guards),
        "components": len(components),
        "infra_resources": len(infra_nodes_raw),
    }

    return FlowDiagram(title="Architecture Overview", view="overview", subgraphs=subgraphs, edges=edges, stats=stats)


def build_ci_view(store: GraphStore) -> FlowDiagram:
    """CI/CD pipeline detail -- shows workflows, jobs, dependencies."""
    subgraphs = []
    edges = []

    # Find CI-related nodes
    all_nodes = store.all_nodes()
    workflows = sorted([n for n in all_nodes if n.kind == NodeKind.MODULE and any(p in n.id for p in ("gha:", "gitlab:"))], key=lambda n: n.id)
    jobs = sorted([n for n in all_nodes if n.kind == NodeKind.METHOD and any(p in n.id for p in ("gha:", "gitlab:"))], key=lambda n: n.id)
    triggers = sorted([n for n in all_nodes if n.kind == NodeKind.CONFIG_KEY and any(p in n.id for p in ("gha:", "gitlab:"))], key=lambda n: n.id)

    # Trigger nodes
    if triggers:
        trigger_flow = [FlowNode(id=f"trigger_{i}", label=t.label, kind="trigger",
                                  properties={"source_id": t.id}) for i, t in enumerate(triggers[:10])]
        subgraphs.append(FlowSubgraph(id="triggers", label="Triggers", nodes=trigger_flow))

    # Group jobs by workflow
    jobs_by_workflow: dict[str, list] = {}
    for job in jobs:
        # Determine workflow from job's module or id prefix
        wf_id = job.module or (job.id.rsplit(":job:", 1)[0] if ":job:" in job.id else "unknown")
        jobs_by_workflow.setdefault(wf_id, []).append(job)

    for wf in workflows:
        wf_jobs = jobs_by_workflow.get(wf.id, [])
        job_nodes = [FlowNode(id=f"job_{j.id.replace(':', '_')}", label=j.label, kind="job",
                              properties={k: v for k, v in j.properties.items() if k in ("stage", "runs_on", "image")})
                     for j in wf_jobs[:20]]
        subgraphs.append(FlowSubgraph(id=f"wf_{wf.id.replace(':', '_')}", label=wf.label, nodes=job_nodes))

    # Job dependency edges
    ci_edges = [e for e in store.all_edges() if e.kind == EdgeKind.DEPENDS_ON and any(p in e.source for p in ("gha:", "gitlab:"))]
    for e in sorted(ci_edges, key=lambda x: (x.source, x.target)):
        edges.append(FlowEdge(source=f"job_{e.source.replace(':', '_')}", target=f"job_{e.target.replace(':', '_')}", label="needs"))

    # Trigger -> workflow edges
    if triggers and workflows:
        for wf in workflows:
            edges.append(FlowEdge(source="trigger_0", target=f"wf_{wf.id.replace(':', '_')}", style="dotted"))

    return FlowDiagram(title="CI/CD Pipeline", view="ci", direction="TD", subgraphs=subgraphs, edges=edges,
                       stats={"workflows": len(workflows), "jobs": len(jobs), "triggers": len(triggers)})


def build_deploy_view(store: GraphStore) -> FlowDiagram:
    """Deployment topology -- K8s, Docker, Terraform resources."""
    subgraphs = []
    edges = []

    all_nodes = store.all_nodes()
    all_edges = store.all_edges()
    infra = sorted([n for n in all_nodes if n.kind in (NodeKind.INFRA_RESOURCE, NodeKind.AZURE_RESOURCE)], key=lambda n: n.id)

    # Group by technology
    k8s = [n for n in infra if "k8s:" in n.id]
    compose = [n for n in infra if "compose:" in n.id]
    tf = [n for n in infra if "tf:" in n.id]
    docker = [n for n in infra if "dockerfile" in n.id.lower() or n.id.startswith("docker:")]
    other = [n for n in infra if n not in k8s and n not in compose and n not in tf and n not in docker]

    def _make_nodes(nodes, prefix, max_nodes=20):
        return [FlowNode(id=f"{prefix}_{i}", label=n.label, kind=prefix,
                         properties={k: v for k, v in n.properties.items() if k in ("kind", "namespace", "image", "resource_type", "provider")})
                for i, n in enumerate(nodes[:max_nodes])]

    if k8s:
        subgraphs.append(FlowSubgraph(id="k8s", label=f"Kubernetes ({len(k8s)} resources)", nodes=_make_nodes(k8s, "k8s")))
    if compose:
        subgraphs.append(FlowSubgraph(id="compose", label=f"Docker Compose ({len(compose)} services)", nodes=_make_nodes(compose, "compose")))
    if tf:
        subgraphs.append(FlowSubgraph(id="terraform", label=f"Terraform ({len(tf)} resources)", nodes=_make_nodes(tf, "tf")))
    if docker:
        subgraphs.append(FlowSubgraph(id="docker", label=f"Docker ({len(docker)} images)", nodes=_make_nodes(docker, "docker")))
    if other:
        subgraphs.append(FlowSubgraph(id="other_infra", label=f"Other ({len(other)})", nodes=_make_nodes(other, "other")))

    # Add CONNECTS_TO and DEPENDS_ON edges between infra nodes
    infra_ids = {n.id for n in infra}
    for e in sorted(all_edges, key=lambda x: (x.source, x.target)):
        if e.source in infra_ids and e.target in infra_ids and e.kind in (EdgeKind.CONNECTS_TO, EdgeKind.DEPENDS_ON):
            # Map to flow node IDs
            src_idx = next((i for i, n in enumerate(infra) if n.id == e.source), None)
            tgt_idx = next((i for i, n in enumerate(infra) if n.id == e.target), None)
            if src_idx is not None and tgt_idx is not None:
                src_node = infra[src_idx]
                tgt_node = infra[tgt_idx]
                # Determine prefix and local index from group membership
                src_prefix, src_local = _resolve_group_index(src_node, k8s, compose, tf, docker, other)
                tgt_prefix, tgt_local = _resolve_group_index(tgt_node, k8s, compose, tf, docker, other)
                edges.append(FlowEdge(source=f"{src_prefix}_{src_local}", target=f"{tgt_prefix}_{tgt_local}"))

    return FlowDiagram(title="Deployment Topology", view="deploy", direction="TD", subgraphs=subgraphs, edges=edges,
                       stats={"k8s": len(k8s), "compose": len(compose), "terraform": len(tf), "docker": len(docker)})


def _resolve_group_index(node, k8s, compose, tf, docker, other):
    """Return (prefix, local_index) for a node within its technology group."""
    if node in k8s:
        return "k8s", k8s.index(node)
    if node in compose:
        return "compose", compose.index(node)
    if node in tf:
        return "tf", tf.index(node)
    if node in docker:
        return "docker", docker.index(node)
    return "other", other.index(node)


def build_runtime_view(store: GraphStore) -> FlowDiagram:
    """Runtime architecture -- modules, endpoints, entities, messaging, grouped by layer."""
    subgraphs = []
    edges = []

    endpoints = store.nodes_by_kind(NodeKind.ENDPOINT)
    entities = store.nodes_by_kind(NodeKind.ENTITY)
    topics = store.nodes_by_kind(NodeKind.TOPIC) + store.nodes_by_kind(NodeKind.QUEUE)
    db_conns = store.nodes_by_kind(NodeKind.DATABASE_CONNECTION)

    # Group by layer
    frontend_nodes = []
    backend_nodes = []
    data_nodes = []

    if endpoints:
        # Group endpoints by layer
        fe_ep = [e for e in endpoints if e.properties.get("layer") == "frontend"]
        be_ep = [e for e in endpoints if e.properties.get("layer") != "frontend"]
        if fe_ep:
            frontend_nodes.append(FlowNode(id="rt_fe_endpoints", label=f"Frontend Routes x{len(fe_ep)}", kind="endpoint"))
        if be_ep:
            backend_nodes.append(FlowNode(id="rt_be_endpoints", label=f"API Endpoints x{len(be_ep)}", kind="endpoint",
                                          properties={"count": len(be_ep)}))

    components = store.nodes_by_kind(NodeKind.COMPONENT)
    if components:
        frontend_nodes.append(FlowNode(id="rt_components", label=f"Components x{len(components)}", kind="component"))

    if entities:
        data_nodes.append(FlowNode(id="rt_entities", label=f"Entities x{len(entities)}", kind="entity"))
    if db_conns:
        data_nodes.append(FlowNode(id="rt_database", label=f"DB Connections x{len(db_conns)}", kind="database"))
    if topics:
        backend_nodes.append(FlowNode(id="rt_messaging", label=f"Messaging x{len(topics)}", kind="messaging"))

    if frontend_nodes:
        subgraphs.append(FlowSubgraph(id="frontend", label="Frontend", nodes=frontend_nodes))
    if backend_nodes:
        subgraphs.append(FlowSubgraph(id="backend", label="Backend", nodes=backend_nodes))
    if data_nodes:
        subgraphs.append(FlowSubgraph(id="data", label="Data Layer", nodes=data_nodes))

    # Edges
    if frontend_nodes and backend_nodes:
        fe_id = frontend_nodes[0].id
        be_id = backend_nodes[0].id
        edges.append(FlowEdge(source=fe_id, target=be_id, label="calls"))
    if backend_nodes and data_nodes:
        be_id = backend_nodes[0].id
        dt_id = data_nodes[0].id
        edges.append(FlowEdge(source=be_id, target=dt_id, label="queries"))

    return FlowDiagram(title="Runtime Architecture", view="runtime", subgraphs=subgraphs, edges=edges,
                       stats={"endpoints": len(endpoints), "entities": len(entities), "components": len(components),
                              "topics": len(topics), "db_connections": len(db_conns)})


def build_auth_view(store: GraphStore) -> FlowDiagram:
    """Auth overview -- guards, endpoints, protection coverage."""
    subgraphs = []
    edges = []

    guards = sorted(store.nodes_by_kind(NodeKind.GUARD), key=lambda n: n.id)
    middleware = sorted(store.nodes_by_kind(NodeKind.MIDDLEWARE), key=lambda n: n.id)
    endpoints = sorted(store.nodes_by_kind(NodeKind.ENDPOINT), key=lambda n: n.id)
    protects_edges = store.edges_by_kind(EdgeKind.PROTECTS)

    protected_ids = {e.target for e in protects_edges}
    protected_endpoints = [e for e in endpoints if e.id in protected_ids]
    unprotected_endpoints = [e for e in endpoints if e.id not in protected_ids]

    # Group guards by auth_type
    guards_by_type: dict[str, list] = {}
    for g in guards:
        auth_type = g.properties.get("auth_type", "unknown")
        guards_by_type.setdefault(auth_type, []).append(g)

    guard_nodes = []
    for auth_type, type_guards in sorted(guards_by_type.items()):
        guard_nodes.append(FlowNode(id=f"auth_{auth_type}", label=f"{auth_type} x{len(type_guards)}", kind="guard",
                                    properties={"auth_type": auth_type, "count": len(type_guards)}))
    if middleware:
        guard_nodes.append(FlowNode(id="auth_middleware", label=f"Middleware x{len(middleware)}", kind="middleware",
                                    properties={"count": len(middleware)}))
    if guard_nodes:
        subgraphs.append(FlowSubgraph(id="guards", label="Auth Guards", nodes=guard_nodes))

    # Endpoint coverage
    ep_nodes = []
    if protected_endpoints:
        ep_nodes.append(FlowNode(id="ep_protected", label=f"Protected x{len(protected_endpoints)}", kind="endpoint",
                                 style="success", properties={"count": len(protected_endpoints)}))
    if unprotected_endpoints:
        ep_nodes.append(FlowNode(id="ep_unprotected", label=f"Unprotected x{len(unprotected_endpoints)}", kind="endpoint",
                                 style="danger", properties={"count": len(unprotected_endpoints)}))
    if ep_nodes:
        subgraphs.append(FlowSubgraph(id="endpoints", label="Endpoints", nodes=ep_nodes))

    # Edges: guards -> protected
    for gn in guard_nodes:
        if any(n.id == "ep_protected" for n in ep_nodes):
            edges.append(FlowEdge(source=gn.id, target="ep_protected", label="protects", style="thick"))

    coverage = len(protected_endpoints) / len(endpoints) * 100 if endpoints else 0

    return FlowDiagram(title="Auth & Security", view="auth", subgraphs=subgraphs, edges=edges,
                       stats={"guards": len(guards), "middleware": len(middleware),
                              "protected": len(protected_endpoints), "unprotected": len(unprotected_endpoints),
                              "coverage_pct": round(coverage, 1)})
