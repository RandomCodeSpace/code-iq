package io.github.randomcodespace.iq.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePackAssembler;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePackRequest;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadata;
import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.intelligence.query.CapabilityMatrix;
// Note: No Analyzer import — MCP server is read-only. Analysis is done via CLI only.
import io.github.randomcodespace.iq.flow.FlowModels.FlowDiagram;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.query.QueryService;
import io.github.randomcodespace.iq.query.StatsService;
import io.github.randomcodespace.iq.query.TopologyService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP tool definitions using Spring AI annotations.
 * Tool names match the Python MCP implementation exactly.
 */
@Component
@Profile("serving")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "codeiq.neo4j.enabled", havingValue = "true", matchIfMissing = true)
public class McpTools {

    private final QueryService queryService;
    private final CodeIqConfig config;
    private final ObjectMapper objectMapper;
    private final FlowEngine flowEngine;
    private final GraphDatabaseService graphDb;
    private final StatsService statsService;
    private final TopologyService topologyService;
    private final GraphStore graphStore;
    private final EvidencePackAssembler evidencePackAssembler;
    private final ArtifactMetadata artifactMetadata;

    public McpTools(QueryService queryService,
                    CodeIqConfig config, ObjectMapper objectMapper,
                    Optional<FlowEngine> flowEngine, GraphDatabaseService graphDb,
                    StatsService statsService, TopologyService topologyService,
                    GraphStore graphStore,
                    Optional<EvidencePackAssembler> evidencePackAssembler,
                    Optional<ArtifactMetadata> artifactMetadata) {
        this.queryService = queryService;
        this.config = config;
        this.objectMapper = objectMapper;
        this.flowEngine = flowEngine.orElse(null);
        this.graphDb = graphDb;
        this.statsService = statsService;
        this.topologyService = topologyService;
        this.graphStore = graphStore;
        this.evidencePackAssembler = evidencePackAssembler.orElse(null);
        this.artifactMetadata = artifactMetadata.orElse(null);
    }

    /**
     * Load graph data on-demand from Neo4j. Data is GC'd after each request
     * instead of being held permanently in heap.
     * <p>
     * TODO: Refactor TopologyService to use Cypher queries instead of in-memory traversal
     * so that topology tools don't need to load the full graph per request.
     */
    private CacheData getCachedData() {
        List<CodeNode> nodes = graphStore.findAll();
        List<CodeEdge> edges = nodes.stream()
                .flatMap(n -> n.getEdges().stream())
                .toList();
        if (nodes.isEmpty()) {
            throw new RuntimeException("No analysis data available. Run 'code-iq analyze' first.");
        }
        return new CacheData(nodes, edges);
    }

    @McpTool(name = "get_stats", description = "Get project graph statistics - node counts, edge counts, backend info.")
    public String getStats() {
        try {
            return toJson(queryService.getStats());
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "get_detailed_stats", description = "Get rich categorized statistics: frameworks, infra, connections, auth, architecture. Category: all, graph, languages, frameworks, infra, connections, auth, architecture.")
    public String getDetailedStats(
            @McpToolParam(description = "Category filter (default: all)", required = false) String category) {
        try {
            return toJson(queryService.getDetailedStats(category != null ? category : "all"));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "query_nodes", description = "Query nodes in the code graph. Filter by kind (endpoint, entity, guard, class, method, component, module, etc.).")
    public String queryNodes(
            @McpToolParam(description = "Node kind filter", required = false) String kind,
            @McpToolParam(description = "Max results", required = false) Integer limit) {
        try {
            return toJson(queryService.listNodes(kind, limit != null ? limit : 50, 0));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "query_edges", description = "Query edges in the code graph. Filter by kind (calls, imports, depends_on, queries, protects, etc.).")
    public String queryEdges(
            @McpToolParam(description = "Edge kind filter", required = false) String kind,
            @McpToolParam(description = "Max results", required = false) Integer limit) {
        try {
            return toJson(queryService.listEdges(kind, limit != null ? limit : 50, 0));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "get_node_neighbors", description = "Get all nodes connected to a given node. Direction: both, in, out.")
    public String getNodeNeighbors(
            @McpToolParam(description = "Node ID") String nodeId,
            @McpToolParam(description = "Direction: both, in, out", required = false) String direction) {
        try {
            return toJson(queryService.getNeighbors(nodeId, direction != null ? direction : "both"));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "get_ego_graph", description = "Get the subgraph within N hops of a center node. Returns all nodes and edges in the neighborhood.")
    public String getEgoGraph(
            @McpToolParam(description = "Center node ID") String center,
            @McpToolParam(description = "Radius (max hops)", required = false) Integer radius) {
        try {
            return toJson(queryService.egoGraph(center, radius != null ? radius : 2));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_cycles", description = "Find circular dependency cycles in the graph.")
    public String findCycles(
            @McpToolParam(description = "Max cycles to return", required = false) Integer limit) {
        try {
            return toJson(queryService.findCycles(limit != null ? limit : 100));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_shortest_path", description = "Find the shortest path between two nodes.")
    public String findShortestPath(
            @McpToolParam(description = "Source node ID") String source,
            @McpToolParam(description = "Target node ID") String target) {
        try {
            Map<String, Object> result = queryService.shortestPath(source, target);
            if (result == null) {
                return toJson(Map.of("error", "No path found between " + source + " and " + target));
            }
            return toJson(result);
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_consumers", description = "Find nodes that consume from a target (CONSUMES/LISTENS edges).")
    public String findConsumers(
            @McpToolParam(description = "Target node ID") String targetId) {
        try {
            return toJson(queryService.consumersOf(targetId));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_producers", description = "Find nodes that produce to a target (PRODUCES/PUBLISHES edges).")
    public String findProducers(
            @McpToolParam(description = "Target node ID") String targetId) {
        try {
            return toJson(queryService.producersOf(targetId));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_callers", description = "Find nodes that call a target (CALLS edges).")
    public String findCallers(
            @McpToolParam(description = "Target node ID") String targetId) {
        try {
            return toJson(queryService.callersOf(targetId));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_dependencies", description = "Find modules that a given module depends on.")
    public String findDependencies(
            @McpToolParam(description = "Module node ID") String moduleId) {
        try {
            return toJson(queryService.dependenciesOf(moduleId));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_dependents", description = "Find modules that depend on a given module.")
    public String findDependents(
            @McpToolParam(description = "Module node ID") String moduleId) {
        try {
            return toJson(queryService.dependentsOf(moduleId));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_dead_code", description = "Find potentially dead code - classes, methods, or interfaces with no incoming calls, imports, or references.")
    public String findDeadCode(
            @McpToolParam(description = "Filter by node kind (class, method, interface)", required = false) String kind,
            @McpToolParam(description = "Max results", required = false) Integer limit) {
        try {
            int safeLimit = limit != null ? Math.min(limit, 1000) : 100;
            return toJson(queryService.findDeadCode(kind, safeLimit));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "generate_flow", description = "Generate an architecture flow diagram. Views: overview, ci, deploy, runtime, auth. Formats: json, mermaid.")
    public String generateFlow(
            @McpToolParam(description = "View name", required = false) String view,
            @McpToolParam(description = "Output format", required = false) String format) {
        String viewName = view != null ? view : "overview";
        String fmt = format != null ? format : "json";
        try {
            FlowEngine engine = resolveFlowEngine();
            if (engine == null) {
                return toJson(Map.of("error", "No analysis data available. Run 'code-iq analyze' first."));
            }
            FlowDiagram diagram = engine.generate(viewName);
            String rendered = engine.render(diagram, fmt);
            return rendered;
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return toJson(error);
        }
    }

    // analyze_codebase removed — MCP server runs on remote hosts where
    // source code is not available (only the bundled graph). Analysis is
    // done locally via CLI: code-iq analyze / code-iq index

    @McpTool(name = "run_cypher", description = "Execute a read-only Cypher query against the Neo4j graph database.")
    public String runCypher(
            @McpToolParam(description = "Cypher query string") String query) {
        // Block any mutation keywords anywhere in the query (defense-in-depth)
        String upper = query.trim().toUpperCase();
        List<String> BLOCKED_PATTERNS = List.of(
                "\\bCREATE\\b", "\\bDELETE\\b", "\\bDETACH\\b", "\\bSET\\b",
                "\\bREMOVE\\b", "\\bMERGE\\b", "\\bDROP\\b", "\\bFOREACH\\b",
                "\\bLOAD\\s+CSV\\b", "\\bCALL\\b");
        for (String pattern : BLOCKED_PATTERNS) {
            if (java.util.regex.Pattern.compile(pattern).matcher(upper).find()) {
                String keyword = pattern.replace("\\b", "").replace("\\s+", " ");
                return toJson(Map.of("error", "Read-only queries only. Mutation keyword found: " + keyword));
            }
        }
        try {
            List<Map<String, Object>> rows = new ArrayList<>();
            try (var tx = graphDb.beginTx();
                 Result result = tx.execute(query)) {
                List<String> columns = result.columns();
                while (result.hasNext()) {
                    Map<String, Object> row = result.next();
                    Map<String, Object> serializable = new LinkedHashMap<>();
                    for (String col : columns) {
                        Object val = row.get(col);
                        serializable.put(col, toSerializable(val));
                    }
                    rows.add(serializable);
                }
                // Do NOT call tx.commit() — read-only, just let it close
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("rows", rows);
            response.put("count", rows.size());
            return toJson(response);
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    // --- Agentic triage tools ---

    @McpTool(name = "find_component_by_file", description = "Given a file path, find the component/module it belongs to, its layer, and all connected nodes.")
    public String findComponentByFile(
            @McpToolParam(description = "File path (relative to codebase root)") String filePath) {
        try {
            return toJson(queryService.findComponentByFile(filePath));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "trace_impact", description = "Trace downstream impact of a node - what depends on it, what breaks if it fails.")
    public String traceImpact(
            @McpToolParam(description = "Node ID") String nodeId,
            @McpToolParam(description = "Max depth", required = false) Integer depth) {
        try {
            return toJson(queryService.traceImpact(nodeId, depth != null ? depth : 3));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_related_endpoints", description = "Given a file, class, or entity name, find all API endpoints that interact with it.")
    public String findRelatedEndpoints(
            @McpToolParam(description = "File, class, or entity identifier") String identifier) {
        try {
            return toJson(queryService.findRelatedEndpoints(identifier));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "search_graph", description = "Free-text search across node labels, IDs, and properties.")
    public String searchGraph(
            @McpToolParam(description = "Search query") String query,
            @McpToolParam(description = "Max results", required = false) Integer limit) {
        try {
            return toJson(queryService.searchGraph(query, limit != null ? limit : 20));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "get_capabilities", description = "Return the capability matrix declaring per-language analysis fidelity levels (EXACT/PARTIAL/LEXICAL_ONLY/UNSUPPORTED) for each intelligence dimension. Optionally filter by a single language.")
    public String getCapabilities(
            @McpToolParam(description = "Language to filter (e.g. java, python). Omit for the full matrix.", required = false) String language) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            if (language != null && !language.isBlank()) {
                result.put("language", language.strip().toLowerCase());
                Map<String, String> caps = new java.util.TreeMap<>();
                CapabilityMatrix.forLanguage(language)
                        .forEach((dim, lvl) -> caps.put(dim.name().toLowerCase(), lvl.name()));
                result.put("capabilities", caps);
            } else {
                result.put("matrix", CapabilityMatrix.asSerializableMap());
            }
            return toJson(result);
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "read_file", description = "Read a source file from the codebase, optionally a specific line range")
    public String readFile(
            @McpToolParam(description = "File path relative to codebase root") String filePath,
            @McpToolParam(description = "Start line (1-based, optional)", required = false) Integer startLine,
            @McpToolParam(description = "End line (1-based, inclusive, optional)", required = false) Integer endLine) {
        try {
            Path root = Path.of(config.getRootPath()).toAbsolutePath().normalize();
            Path resolved = root.resolve(filePath).normalize();
            // Path traversal protection
            if (!resolved.startsWith(root)) {
                return toJson(Map.of("error", "Path traversal detected"));
            }
            String content = java.nio.file.Files.readString(resolved, java.nio.charset.StandardCharsets.UTF_8);
            if (startLine != null || endLine != null) {
                String[] lines = content.split("\n", -1);
                int start = (startLine != null ? startLine : 1);
                int end = (endLine != null ? endLine : lines.length);
                // Clamp bounds
                start = Math.max(1, Math.min(start, lines.length));
                end = Math.max(start, Math.min(end, lines.length));
                StringBuilder sb = new StringBuilder();
                for (int i = start - 1; i < end; i++) {
                    if (i > start - 1) sb.append('\n');
                    sb.append(lines[i]);
                }
                return sb.toString();
            }
            return content;
        } catch (Exception e) {
            return toJson(Map.of("error", "Failed to read file: " + e.getMessage()));
        }
    }

    // --- Topology tools ---

    @McpTool(name = "get_topology", description = "Get service topology map — services, infrastructure nodes (databases, queues, caches), and connections between them.")
    public String getTopology() {
        try {
            return toJson(queryService.getTopology());
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "service_detail", description = "Get detailed view of a specific service")
    public String serviceDetail(
            @McpToolParam(description = "Service name") String serviceName) {
        try {
            var data = getCachedData();
            return toJson(topologyService.serviceDetail(serviceName, data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "service_dependencies", description = "Get dependencies of a service (databases, queues, other services)")
    public String serviceDependencies(
            @McpToolParam(description = "Service name") String serviceName) {
        try {
            var data = getCachedData();
            return toJson(topologyService.serviceDependencies(serviceName, data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "service_dependents", description = "Get services that depend on this service")
    public String serviceDependents(
            @McpToolParam(description = "Service name") String serviceName) {
        try {
            var data = getCachedData();
            return toJson(topologyService.serviceDependents(serviceName, data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "blast_radius", description = "Analyze blast radius — what's affected if this node changes")
    public String blastRadius(
            @McpToolParam(description = "Node ID") String nodeId) {
        try {
            var data = getCachedData();
            return toJson(topologyService.blastRadius(nodeId, data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_path", description = "Find connection path between two services")
    public String findPath(
            @McpToolParam(description = "Source service") String source,
            @McpToolParam(description = "Target service") String target) {
        try {
            var data = getCachedData();
            return toJson(topologyService.findPath(source, target, data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_bottlenecks", description = "Find bottleneck services with most connections")
    public String findBottlenecks() {
        try {
            var data = getCachedData();
            return toJson(topologyService.findBottlenecks(data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_circular_deps", description = "Find circular service-to-service dependencies")
    public String findCircularDeps() {
        try {
            var data = getCachedData();
            return toJson(topologyService.findCircularDeps(data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_dead_services", description = "Find dead services with no incoming connections")
    public String findDeadServices() {
        try {
            var data = getCachedData();
            return toJson(topologyService.findDeadServices(data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "find_node", description = "Find a node by name — exact match priority, then partial")
    public String findNode(
            @McpToolParam(description = "Search query") String query) {
        try {
            var data = getCachedData();
            return toJson(topologyService.findNode(query, data.nodes()));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "get_evidence_pack", description = "Assemble an evidence pack for a symbol or file. Returns matched nodes, snippets, provenance, and degradation notes. Provide symbol name and/or file path.")
    public String getEvidencePack(
            @McpToolParam(description = "Symbol name to look up (e.g. UserService, handleLogin)", required = false) String symbol,
            @McpToolParam(description = "File path relative to repo root", required = false) String filePath,
            @McpToolParam(description = "Max lines per snippet (default: config value)", required = false) Integer maxSnippetLines,
            @McpToolParam(description = "Include cross-reference nodes (default: false)", required = false) Boolean includeReferences) {
        if (evidencePackAssembler == null) {
            return toJson(Map.of("error", "Evidence pack service unavailable. Run 'enrich' first."));
        }
        try {
            EvidencePackRequest request = new EvidencePackRequest(
                    symbol, filePath, maxSnippetLines,
                    Boolean.TRUE.equals(includeReferences));
            return toJson(evidencePackAssembler.assemble(request, artifactMetadata));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @McpTool(name = "get_artifact_metadata", description = "Return artifact metadata: repo identity, commit SHA, build timestamp, extractor versions, capability matrix snapshot, and integrity hash.")
    public String getArtifactMetadata() {
        if (artifactMetadata == null) {
            return toJson(Map.of("error", "Artifact metadata unavailable. Run 'enrich' first."));
        }
        try {
            return toJson(artifactMetadata);
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Resolve FlowEngine: use injected instance if available, otherwise create from H2 cache.
     */
    private FlowEngine resolveFlowEngine() {
        if (flowEngine != null) return flowEngine;
        try {
            CacheData data = getCachedData();
            if (data.nodes().isEmpty()) return null;
            return FlowEngine.fromCache(data.nodes());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private record CacheData(List<CodeNode> nodes, List<CodeEdge> edges) {}

    /**
     * Convert Neo4j node/relationship values to JSON-serializable types.
     */
    private Object toSerializable(Object val) {
        if (val == null) return null;
        if (val instanceof org.neo4j.graphdb.Node node) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("_id", node.getElementId());
            List<String> labels = new ArrayList<>();
            node.getLabels().forEach(l -> labels.add(l.name()));
            map.put("_labels", labels);
            for (String key : node.getPropertyKeys()) {
                map.put(key, node.getProperty(key));
            }
            return map;
        }
        if (val instanceof org.neo4j.graphdb.Relationship rel) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("_id", rel.getElementId());
            map.put("_type", rel.getType().name());
            map.put("_start", rel.getStartNode().getElementId());
            map.put("_end", rel.getEndNode().getElementId());
            for (String key : rel.getPropertyKeys()) {
                map.put(key, rel.getProperty(key));
            }
            return map;
        }
        if (val instanceof org.neo4j.graphdb.Path path) {
            List<Object> nodes = new ArrayList<>();
            for (var node : path.nodes()) {
                nodes.add(toSerializable(node));
            }
            return Map.of("nodes", nodes, "length", path.length());
        }
        if (val instanceof Iterable<?> iter) {
            List<Object> list = new ArrayList<>();
            for (var item : iter) {
                list.add(toSerializable(item));
            }
            return list;
        }
        return val;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{\"error\": \"Serialization failed: " + e.getMessage() + "\"}";
        }
    }
}
