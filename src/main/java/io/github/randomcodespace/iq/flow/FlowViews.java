package io.github.randomcodespace.iq.flow;

import io.github.randomcodespace.iq.flow.FlowModels.FlowDiagram;
import io.github.randomcodespace.iq.flow.FlowModels.FlowEdge;
import io.github.randomcodespace.iq.flow.FlowModels.FlowNode;
import io.github.randomcodespace.iq.flow.FlowModels.FlowSubgraph;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Flow view generators -- each produces a small, clean FlowDiagram from the full graph.
 * Mirrors the Python implementation's 5 views exactly.
 */
public final class FlowViews {
    private static final String PROP_LR = "LR";
    private static final String PROP_APP_ENDPOINTS = "app_endpoints";
    private static final String PROP_APP_MESSAGING = "app_messaging";
    private static final String PROP_CI = "ci";
    private static final String PROP_CI_JOBS = "ci_jobs";
    private static final String PROP_CI_PIPELINES = "ci_pipelines";
    private static final String PROP_COMPOSE = "compose";
    private static final String PROP_COUNT = "count";
    private static final String PROP_DOCKER = "docker";
    private static final String PROP_ENDPOINT = "endpoint";
    private static final String PROP_ENDPOINTS = "endpoints";
    private static final String PROP_EP_PROTECTED = "ep_protected";
    private static final String PROP_FRONTEND = "frontend";
    private static final String PROP_GUARDS = "guards";
    private static final String PROP_INFRA = "infra";
    private static final String PROP_JOB_ = "job_";
    private static final String PROP_K8S = "k8s";
    private static final String PROP_MIDDLEWARE = "middleware";
    private static final String PROP_TERRAFORM = "terraform";


    private static final String GITLAB_PREFIX = "gitlab:";

    private FlowViews() {
    }

    /**
     * High-level overview with 4 subgraphs: CI, Infrastructure, Application, Security.
     */
    public static FlowDiagram buildOverview(FlowDataSource store) {
        var subgraphs = new ArrayList<FlowSubgraph>();
        var edges = new ArrayList<FlowEdge>();

        List<CodeNode> allNodes = store.findAll();

        // CI/CD subgraph
        var ciNodes = new ArrayList<FlowNode>();
        List<CodeNode> workflows = allNodes.stream()
                .filter(n -> n.getKind() == NodeKind.MODULE)
                .filter(n -> isCiNode(n.getId()))
                .toList();
        List<CodeNode> ciJobs = allNodes.stream()
                .filter(n -> n.getKind() == NodeKind.METHOD)
                .filter(n -> isCiNode(n.getId()))
                .toList();
        if (!workflows.isEmpty() || !ciJobs.isEmpty()) {
            ciNodes.add(new FlowNode(PROP_CI_PIPELINES, "Pipelines x" + workflows.size(), "pipeline",
                    Map.of(PROP_COUNT, workflows.size())));
            if (!ciJobs.isEmpty()) {
                ciNodes.add(new FlowNode(PROP_CI_JOBS, "Jobs x" + ciJobs.size(), "job",
                        Map.of(PROP_COUNT, ciJobs.size())));
                edges.add(new FlowEdge(PROP_CI_PIPELINES, PROP_CI_JOBS));
            }
            subgraphs.add(new FlowSubgraph(PROP_CI, "CI/CD Pipeline", ciNodes, PROP_CI));
        }

        // Infrastructure subgraph
        List<CodeNode> infraNodesRaw = new ArrayList<>();
        infraNodesRaw.addAll(store.findByKind(NodeKind.INFRA_RESOURCE));
        infraNodesRaw.addAll(store.findByKind(NodeKind.AZURE_RESOURCE));
        if (!infraNodesRaw.isEmpty()) {
            List<CodeNode> k8s = infraNodesRaw.stream().filter(n -> n.getId().contains("k8s:")).toList();
            List<CodeNode> docker = infraNodesRaw.stream()
                    .filter(n -> n.getId().contains("compose:") || n.getId().toLowerCase().contains("dockerfile"))
                    .toList();
            List<CodeNode> terraform = infraNodesRaw.stream().filter(n -> n.getId().contains("tf:")).toList();
            Set<CodeNode> grouped = new java.util.HashSet<>();
            grouped.addAll(k8s);
            grouped.addAll(docker);
            grouped.addAll(terraform);
            List<CodeNode> otherInfra = infraNodesRaw.stream().filter(n -> !grouped.contains(n)).toList();

            var infraFlowNodes = new ArrayList<FlowNode>();
            if (!k8s.isEmpty()) {
                infraFlowNodes.add(new FlowNode("infra_k8s", "K8s Resources x" + k8s.size(), PROP_K8S,
                        Map.of(PROP_COUNT, k8s.size())));
            }
            if (!docker.isEmpty()) {
                infraFlowNodes.add(new FlowNode("infra_docker", "Docker x" + docker.size(), PROP_DOCKER,
                        Map.of(PROP_COUNT, docker.size())));
            }
            if (!terraform.isEmpty()) {
                infraFlowNodes.add(new FlowNode("infra_tf", "Terraform x" + terraform.size(), PROP_TERRAFORM,
                        Map.of(PROP_COUNT, terraform.size())));
            }
            if (!otherInfra.isEmpty()) {
                infraFlowNodes.add(new FlowNode("infra_other", "Infra x" + otherInfra.size(), PROP_INFRA,
                        Map.of(PROP_COUNT, otherInfra.size())));
            }
            if (!infraFlowNodes.isEmpty()) {
                subgraphs.add(new FlowSubgraph(PROP_INFRA, "Infrastructure", infraFlowNodes, "deploy"));
            }
        }

        // Application subgraph
        List<CodeNode> endpoints = store.findByKind(NodeKind.ENDPOINT);
        List<CodeNode> entities = store.findByKind(NodeKind.ENTITY);
        List<CodeNode> classes = store.findByKind(NodeKind.CLASS);
        List<CodeNode> methods = store.findByKind(NodeKind.METHOD);
        List<CodeNode> appMethods = methods.stream().filter(m -> !isCiNode(m.getId())).toList();
        List<CodeNode> components = store.findByKind(NodeKind.COMPONENT);
        var topics = new ArrayList<CodeNode>();
        topics.addAll(store.findByKind(NodeKind.TOPIC));
        topics.addAll(store.findByKind(NodeKind.QUEUE));
        List<CodeNode> dbConns = store.findByKind(NodeKind.DATABASE_CONNECTION);

        var appNodes = new ArrayList<FlowNode>();
        if (!endpoints.isEmpty()) {
            appNodes.add(new FlowNode(PROP_APP_ENDPOINTS, "Endpoints x" + endpoints.size(), PROP_ENDPOINT,
                    Map.of(PROP_COUNT, endpoints.size())));
        }
        if (!entities.isEmpty()) {
            appNodes.add(new FlowNode("app_entities", "Entities x" + entities.size(), "entity",
                    Map.of(PROP_COUNT, entities.size())));
        }
        if (!components.isEmpty()) {
            appNodes.add(new FlowNode("app_components", "Components x" + components.size(), "component",
                    Map.of(PROP_COUNT, components.size())));
        }
        if (!topics.isEmpty()) {
            appNodes.add(new FlowNode(PROP_APP_MESSAGING, "Topics/Queues x" + topics.size(), "messaging",
                    Map.of(PROP_COUNT, topics.size())));
        }
        if (!dbConns.isEmpty()) {
            appNodes.add(new FlowNode("app_database", "DB Connections x" + dbConns.size(), "database",
                    Map.of(PROP_COUNT, dbConns.size())));
        }
        if (appNodes.isEmpty() && (!classes.isEmpty() || !appMethods.isEmpty())) {
            appNodes.add(new FlowNode("app_code",
                    "Classes x" + classes.size() + ", Methods x" + appMethods.size(), "code",
                    Map.of("classes", classes.size(), "methods", appMethods.size())));
        }
        if (!appNodes.isEmpty()) {
            subgraphs.add(new FlowSubgraph("app", "Application", appNodes, "runtime"));
            if (!endpoints.isEmpty() && !entities.isEmpty()) {
                edges.add(new FlowEdge(PROP_APP_ENDPOINTS, "app_entities", "queries"));
            }
            if (!endpoints.isEmpty() && appNodes.stream().anyMatch(n -> "app_messaging".equals(n.id()))) {
                edges.add(new FlowEdge(PROP_APP_ENDPOINTS, PROP_APP_MESSAGING, null, "dotted"));
            }
        }

        // Security subgraph
        List<CodeNode> guards = store.findByKind(NodeKind.GUARD);
        List<CodeNode> middleware = store.findByKind(NodeKind.MIDDLEWARE);
        if (!guards.isEmpty() || !middleware.isEmpty()) {
            var secNodes = new ArrayList<FlowNode>();
            if (!guards.isEmpty()) {
                secNodes.add(new FlowNode("sec_guards", "Auth Guards x" + guards.size(), "guard",
                        Map.of(PROP_COUNT, guards.size())));
            }
            if (!middleware.isEmpty()) {
                secNodes.add(new FlowNode("sec_middleware", "Middleware x" + middleware.size(), PROP_MIDDLEWARE,
                        Map.of(PROP_COUNT, middleware.size())));
            }
            subgraphs.add(new FlowSubgraph("security", "Security", secNodes, "auth"));
            if (!guards.isEmpty() && !endpoints.isEmpty()) {
                edges.add(new FlowEdge("sec_guards", PROP_APP_ENDPOINTS, "protects", "thick"));
            }
        }

        // Cross-subgraph edges
        if ((!ciNodes.isEmpty()) && !infraNodesRaw.isEmpty()) {
            var infraSg = subgraphs.stream().filter(sg -> "infra".equals(sg.id())).findFirst();
            if (infraSg.isPresent() && !infraSg.get().nodes().isEmpty()) {
                String firstInfra = infraSg.get().nodes().getFirst().id();
                String ciSource = !ciJobs.isEmpty() ? PROP_CI_JOBS : PROP_CI_PIPELINES;
                edges.add(new FlowEdge(ciSource, firstInfra, "deploys"));
            }
        }
        if (!infraNodesRaw.isEmpty() && !appNodes.isEmpty()) {
            var infraSg = subgraphs.stream().filter(sg -> "infra".equals(sg.id())).findFirst();
            if (infraSg.isPresent() && !infraSg.get().nodes().isEmpty()) {
                String firstInfra = infraSg.get().nodes().getFirst().id();
                edges.add(new FlowEdge(firstInfra, appNodes.getFirst().id(), "hosts"));
            }
        }

        var stats = new LinkedHashMap<String, Object>();
        stats.put("total_nodes", allNodes.size());
        stats.put("total_edges", countEdges(allNodes));
        stats.put(PROP_ENDPOINTS, endpoints.size());
        stats.put("entities", entities.size());
        stats.put(PROP_GUARDS, guards.size());
        stats.put("components", components.size());
        stats.put("infra_resources", infraNodesRaw.size());

        return new FlowDiagram("Architecture Overview", "overview", PROP_LR,
                subgraphs, List.of(), edges, stats);
    }

    /**
     * CI/CD pipeline detail -- shows workflows, jobs, dependencies.
     */
    public static FlowDiagram buildCiView(FlowDataSource store) {
        var subgraphs = new ArrayList<FlowSubgraph>();
        var edges = new ArrayList<FlowEdge>();

        List<CodeNode> allNodes = store.findAll();
        List<CodeNode> workflows = allNodes.stream()
                .filter(n -> n.getKind() == NodeKind.MODULE && isCiNode(n.getId()))
                .sorted(Comparator.comparing(CodeNode::getId))
                .toList();
        List<CodeNode> jobs = allNodes.stream()
                .filter(n -> n.getKind() == NodeKind.METHOD && isCiNode(n.getId()))
                .sorted(Comparator.comparing(CodeNode::getId))
                .toList();
        List<CodeNode> triggers = allNodes.stream()
                .filter(n -> n.getKind() == NodeKind.CONFIG_KEY && isCiNode(n.getId()))
                .sorted(Comparator.comparing(CodeNode::getId))
                .toList();

        // Trigger nodes
        if (!triggers.isEmpty()) {
            var triggerFlow = new ArrayList<FlowNode>();
            int max = Math.min(triggers.size(), 10);
            for (int i = 0; i < max; i++) {
                triggerFlow.add(new FlowNode("trigger_" + i, triggers.get(i).getLabel(), "trigger",
                        Map.of("source_id", triggers.get(i).getId())));
            }
            subgraphs.add(new FlowSubgraph("triggers", "Triggers", triggerFlow));
        }

        // Group jobs by workflow
        Map<String, List<CodeNode>> jobsByWorkflow = new TreeMap<>();
        for (var job : jobs) {
            String wfId = job.getModule();
            if (wfId == null) {
                wfId = job.getId().contains(":job:") ? job.getId().split(":job:")[0] : "unknown";
            }
            jobsByWorkflow.computeIfAbsent(wfId, k -> new ArrayList<>()).add(job);
        }

        for (var wf : workflows) {
            List<CodeNode> wfJobs = jobsByWorkflow.getOrDefault(wf.getId(), List.of());
            var jobNodes = new ArrayList<FlowNode>();
            int max = Math.min(wfJobs.size(), 20);
            for (int i = 0; i < max; i++) {
                var j = wfJobs.get(i);
                Map<String, Object> props = new LinkedHashMap<>();
                for (var key : List.of("stage", "runs_on", "image")) {
                    if (j.getProperties().containsKey(key)) {
                        props.put(key, j.getProperties().get(key));
                    }
                }
                jobNodes.add(new FlowNode(PROP_JOB_ + j.getId().replace(":", "_"), j.getLabel(), "job", props));
            }
            subgraphs.add(new FlowSubgraph("wf_" + wf.getId().replace(":", "_"), wf.getLabel(), jobNodes));
        }

        // Job dependency edges from graph edges
        for (var node : allNodes) {
            if (!isCiNode(node.getId())) continue;
            for (var edge : node.getEdges()) {
                if (edge.getKind() == EdgeKind.DEPENDS_ON && isCiNode(edge.getSourceId())) {
                    edges.add(new FlowEdge(
                            PROP_JOB_ + edge.getSourceId().replace(":", "_"),
                            PROP_JOB_ + edge.getTarget().getId().replace(":", "_"),
                            "needs"));
                }
            }
        }
        // Sort edges for determinism
        edges.sort(Comparator.comparing(FlowEdge::source).thenComparing(FlowEdge::target));

        // Trigger -> workflow edges
        if (!triggers.isEmpty() && !workflows.isEmpty()) {
            for (var wf : workflows) {
                edges.add(new FlowEdge("trigger_0", "wf_" + wf.getId().replace(":", "_"), null, "dotted"));
            }
        }

        var stats = new LinkedHashMap<String, Object>();
        stats.put("workflows", workflows.size());
        stats.put("jobs", jobs.size());
        stats.put("triggers", triggers.size());

        return new FlowDiagram("CI/CD Pipeline", PROP_CI, "TD",
                subgraphs, List.of(), edges, stats);
    }

    /**
     * Deployment topology -- K8s, Docker, Terraform resources.
     */
    public static FlowDiagram buildDeployView(FlowDataSource store) {
        var subgraphs = new ArrayList<FlowSubgraph>();
        var edges = new ArrayList<FlowEdge>();

        List<CodeNode> allNodes = store.findAll();
        List<CodeNode> infra = allNodes.stream()
                .filter(n -> n.getKind() == NodeKind.INFRA_RESOURCE || n.getKind() == NodeKind.AZURE_RESOURCE)
                .sorted(Comparator.comparing(CodeNode::getId))
                .toList();

        List<CodeNode> k8s = infra.stream().filter(n -> n.getId().contains("k8s:")).toList();
        List<CodeNode> compose = infra.stream().filter(n -> n.getId().contains("compose:")).toList();
        List<CodeNode> tf = infra.stream().filter(n -> n.getId().contains("tf:")).toList();
        List<CodeNode> docker = infra.stream()
                .filter(n -> n.getId().toLowerCase().contains("dockerfile") || n.getId().startsWith("docker:"))
                .toList();
        Set<CodeNode> grouped = new java.util.HashSet<>();
        grouped.addAll(k8s);
        grouped.addAll(compose);
        grouped.addAll(tf);
        grouped.addAll(docker);
        List<CodeNode> other = infra.stream().filter(n -> !grouped.contains(n)).toList();

        if (!k8s.isEmpty()) {
            subgraphs.add(new FlowSubgraph(PROP_K8S, "Kubernetes (" + k8s.size() + " resources)",
                    makeNodes(k8s, PROP_K8S, 20)));
        }
        if (!compose.isEmpty()) {
            subgraphs.add(new FlowSubgraph(PROP_COMPOSE, "Docker Compose (" + compose.size() + " services)",
                    makeNodes(compose, PROP_COMPOSE, 20)));
        }
        if (!tf.isEmpty()) {
            subgraphs.add(new FlowSubgraph(PROP_TERRAFORM, "Terraform (" + tf.size() + " resources)",
                    makeNodes(tf, "tf", 20)));
        }
        if (!docker.isEmpty()) {
            subgraphs.add(new FlowSubgraph(PROP_DOCKER, "Docker (" + docker.size() + " images)",
                    makeNodes(docker, PROP_DOCKER, 20)));
        }
        if (!other.isEmpty()) {
            subgraphs.add(new FlowSubgraph("other_infra", "Other (" + other.size() + ")",
                    makeNodes(other, "other", 20)));
        }

        // Add CONNECTS_TO and DEPENDS_ON edges between infra nodes
        Set<String> infraIds = infra.stream().map(CodeNode::getId).collect(Collectors.toSet());
        for (var node : allNodes) {
            for (var edge : node.getEdges()) {
                if (infraIds.contains(edge.getSourceId()) && edge.getTarget() != null
                        && infraIds.contains(edge.getTarget().getId())
                        && (edge.getKind() == EdgeKind.CONNECTS_TO || edge.getKind() == EdgeKind.DEPENDS_ON)) {
                    CodeNode srcNode = infra.stream().filter(n -> n.getId().equals(edge.getSourceId())).findFirst().orElse(null);
                    CodeNode tgtNode = infra.stream().filter(n -> n.getId().equals(edge.getTarget().getId())).findFirst().orElse(null);
                    if (srcNode != null && tgtNode != null) {
                        var srcGroup = resolveGroupIndex(srcNode, k8s, compose, tf, docker, other);
                        var tgtGroup = resolveGroupIndex(tgtNode, k8s, compose, tf, docker, other);
                        edges.add(new FlowEdge(srcGroup[0] + "_" + srcGroup[1], tgtGroup[0] + "_" + tgtGroup[1]));
                    }
                }
            }
        }

        var stats = new LinkedHashMap<String, Object>();
        stats.put(PROP_K8S, k8s.size());
        stats.put(PROP_COMPOSE, compose.size());
        stats.put(PROP_TERRAFORM, tf.size());
        stats.put(PROP_DOCKER, docker.size());

        return new FlowDiagram("Deployment Topology", "deploy", "TD",
                subgraphs, List.of(), edges, stats);
    }

    /**
     * Runtime architecture -- modules, endpoints, entities, messaging, grouped by layer.
     */
    public static FlowDiagram buildRuntimeView(FlowDataSource store) {
        var subgraphs = new ArrayList<FlowSubgraph>();
        var edges = new ArrayList<FlowEdge>();

        List<CodeNode> endpoints = store.findByKind(NodeKind.ENDPOINT);
        List<CodeNode> entities = store.findByKind(NodeKind.ENTITY);
        var topics = new ArrayList<CodeNode>();
        topics.addAll(store.findByKind(NodeKind.TOPIC));
        topics.addAll(store.findByKind(NodeKind.QUEUE));
        List<CodeNode> dbConns = store.findByKind(NodeKind.DATABASE_CONNECTION);
        List<CodeNode> components = store.findByKind(NodeKind.COMPONENT);

        var frontendNodes = new ArrayList<FlowNode>();
        var backendNodes = new ArrayList<FlowNode>();
        var dataNodes = new ArrayList<FlowNode>();

        if (!endpoints.isEmpty()) {
            List<CodeNode> feEp = endpoints.stream()
                    .filter(e -> "frontend".equals(e.getProperties().get("layer")))
                    .toList();
            List<CodeNode> beEp = endpoints.stream()
                    .filter(e -> !"frontend".equals(e.getProperties().get("layer")))
                    .toList();
            if (!feEp.isEmpty()) {
                frontendNodes.add(new FlowNode("rt_fe_endpoints", "Frontend Routes x" + feEp.size(), PROP_ENDPOINT));
            }
            if (!beEp.isEmpty()) {
                backendNodes.add(new FlowNode("rt_be_endpoints", "API Endpoints x" + beEp.size(), PROP_ENDPOINT,
                        Map.of(PROP_COUNT, beEp.size())));
            }
        }

        if (!components.isEmpty()) {
            frontendNodes.add(new FlowNode("rt_components", "Components x" + components.size(), "component"));
        }

        if (!entities.isEmpty()) {
            dataNodes.add(new FlowNode("rt_entities", "Entities x" + entities.size(), "entity"));
        }
        if (!dbConns.isEmpty()) {
            dataNodes.add(new FlowNode("rt_database", "DB Connections x" + dbConns.size(), "database"));
        }
        if (!topics.isEmpty()) {
            backendNodes.add(new FlowNode("rt_messaging", "Messaging x" + topics.size(), "messaging"));
        }

        if (!frontendNodes.isEmpty()) {
            subgraphs.add(new FlowSubgraph(PROP_FRONTEND, "Frontend", frontendNodes));
        }
        if (!backendNodes.isEmpty()) {
            subgraphs.add(new FlowSubgraph("backend", "Backend", backendNodes));
        }
        if (!dataNodes.isEmpty()) {
            subgraphs.add(new FlowSubgraph("data", "Data Layer", dataNodes));
        }

        // Edges
        if (!frontendNodes.isEmpty() && !backendNodes.isEmpty()) {
            edges.add(new FlowEdge(frontendNodes.getFirst().id(), backendNodes.getFirst().id(), "calls"));
        }
        if (!backendNodes.isEmpty() && !dataNodes.isEmpty()) {
            edges.add(new FlowEdge(backendNodes.getFirst().id(), dataNodes.getFirst().id(), "queries"));
        }

        var stats = new LinkedHashMap<String, Object>();
        stats.put(PROP_ENDPOINTS, endpoints.size());
        stats.put("entities", entities.size());
        stats.put("components", components.size());
        stats.put("topics", topics.size());
        stats.put("db_connections", dbConns.size());

        return new FlowDiagram("Runtime Architecture", "runtime", PROP_LR,
                subgraphs, List.of(), edges, stats);
    }

    /**
     * Auth overview -- guards, endpoints, protection coverage.
     */
    public static FlowDiagram buildAuthView(FlowDataSource store) {
        var subgraphs = new ArrayList<FlowSubgraph>();
        var edges = new ArrayList<FlowEdge>();

        List<CodeNode> guards = store.findByKind(NodeKind.GUARD).stream()
                .sorted(Comparator.comparing(CodeNode::getId)).toList();
        List<CodeNode> middleware = store.findByKind(NodeKind.MIDDLEWARE).stream()
                .sorted(Comparator.comparing(CodeNode::getId)).toList();
        List<CodeNode> endpoints = store.findByKind(NodeKind.ENDPOINT).stream()
                .sorted(Comparator.comparing(CodeNode::getId)).toList();

        // Find protects edges
        Set<String> protectedIds = new java.util.HashSet<>();
        for (var node : store.findAll()) {
            for (var edge : node.getEdges()) {
                if (edge.getKind() == EdgeKind.PROTECTS && edge.getTarget() != null) {
                    protectedIds.add(edge.getTarget().getId());
                }
            }
        }

        List<CodeNode> protectedEndpoints = endpoints.stream()
                .filter(e -> protectedIds.contains(e.getId())).toList();
        List<CodeNode> unprotectedEndpoints = endpoints.stream()
                .filter(e -> !protectedIds.contains(e.getId())).toList();

        // Group guards by auth_type
        Map<String, List<CodeNode>> guardsByType = new TreeMap<>();
        for (var g : guards) {
            String authType = g.getProperties().getOrDefault("auth_type", "unknown").toString();
            guardsByType.computeIfAbsent(authType, k -> new ArrayList<>()).add(g);
        }

        var guardNodes = new ArrayList<FlowNode>();
        for (var entry : guardsByType.entrySet()) {
            guardNodes.add(new FlowNode("auth_" + entry.getKey(),
                    entry.getKey() + " x" + entry.getValue().size(), "guard",
                    Map.of("auth_type", entry.getKey(), PROP_COUNT, entry.getValue().size())));
        }
        if (!middleware.isEmpty()) {
            guardNodes.add(new FlowNode("auth_middleware", "Middleware x" + middleware.size(), PROP_MIDDLEWARE,
                    Map.of(PROP_COUNT, middleware.size())));
        }
        if (!guardNodes.isEmpty()) {
            subgraphs.add(new FlowSubgraph(PROP_GUARDS, "Auth Guards", guardNodes));
        }

        // Endpoint coverage
        var epNodes = new ArrayList<FlowNode>();
        if (!protectedEndpoints.isEmpty()) {
            epNodes.add(new FlowNode(PROP_EP_PROTECTED, "Protected x" + protectedEndpoints.size(), PROP_ENDPOINT,
                    "success", Map.of(PROP_COUNT, protectedEndpoints.size())));
        }
        if (!unprotectedEndpoints.isEmpty()) {
            epNodes.add(new FlowNode("ep_unprotected", "Unprotected x" + unprotectedEndpoints.size(), PROP_ENDPOINT,
                    "danger", Map.of(PROP_COUNT, unprotectedEndpoints.size())));
        }
        if (!epNodes.isEmpty()) {
            subgraphs.add(new FlowSubgraph(PROP_ENDPOINTS, "Endpoints", epNodes));
        }

        // Edges: guards -> protected
        for (var gn : guardNodes) {
            if (epNodes.stream().anyMatch(n -> "ep_protected".equals(n.id()))) {
                edges.add(new FlowEdge(gn.id(), PROP_EP_PROTECTED, "protects", "thick"));
            }
        }

        double coverage = endpoints.isEmpty() ? 0
                : (double) protectedEndpoints.size() / endpoints.size() * 100;

        var stats = new LinkedHashMap<String, Object>();
        stats.put(PROP_GUARDS, guards.size());
        stats.put(PROP_MIDDLEWARE, middleware.size());
        stats.put("protected", protectedEndpoints.size());
        stats.put("unprotected", unprotectedEndpoints.size());
        stats.put("coverage_pct", Math.round(coverage * 10.0) / 10.0);

        return new FlowDiagram("Auth & Security", "auth", PROP_LR,
                subgraphs, List.of(), edges, stats);
    }

    // --- Helper methods ---

    private static boolean isCiNode(String id) {
        return id.contains("gha:") || id.contains(GITLAB_PREFIX);
    }

    private static List<FlowNode> makeNodes(List<CodeNode> nodes, String prefix, int maxNodes) {
        var result = new ArrayList<FlowNode>();
        int max = Math.min(nodes.size(), maxNodes);
        for (int i = 0; i < max; i++) {
            var n = nodes.get(i);
            Map<String, Object> props = new LinkedHashMap<>();
            for (var key : List.of("kind", "namespace", "image", "resource_type", "provider")) {
                if (n.getProperties().containsKey(key)) {
                    props.put(key, n.getProperties().get(key));
                }
            }
            result.add(new FlowNode(prefix + "_" + i, n.getLabel(), prefix, props));
        }
        return result;
    }

    private static String[] resolveGroupIndex(CodeNode node, List<CodeNode> k8s, List<CodeNode> compose,
                                               List<CodeNode> tf, List<CodeNode> docker, List<CodeNode> other) {
        int idx;
        if ((idx = k8s.indexOf(node)) >= 0) return new String[]{PROP_K8S, String.valueOf(idx)};
        if ((idx = compose.indexOf(node)) >= 0) return new String[]{PROP_COMPOSE, String.valueOf(idx)};
        if ((idx = tf.indexOf(node)) >= 0) return new String[]{"tf", String.valueOf(idx)};
        if ((idx = docker.indexOf(node)) >= 0) return new String[]{PROP_DOCKER, String.valueOf(idx)};
        return new String[]{"other", String.valueOf(other.indexOf(node))};
    }

    private static long countEdges(List<CodeNode> allNodes) {
        return allNodes.stream().mapToLong(n -> n.getEdges().size()).sum();
    }
}
