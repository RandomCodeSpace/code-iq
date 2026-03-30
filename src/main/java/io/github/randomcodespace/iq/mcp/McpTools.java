package io.github.randomcodespace.iq.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.randomcodespace.iq.analyzer.AnalysisResult;
import io.github.randomcodespace.iq.analyzer.Analyzer;
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.flow.FlowModels.FlowDiagram;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.query.QueryService;
import io.github.randomcodespace.iq.query.StatsService;
import io.github.randomcodespace.iq.query.TopologyService;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP tool definitions using Spring AI annotations.
 * Tool names match the Python MCP implementation exactly.
 */
@Component
@Profile("serving")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "codeiq.neo4j.enabled", havingValue = "true", matchIfMissing = true)
public class McpTools {

    private final QueryService queryService;
    private final Analyzer analyzer;
    private final CodeIqConfig config;
    private final ObjectMapper objectMapper;
    private final FlowEngine flowEngine;
    private final GraphDatabaseService graphDb;
    private final StatsService statsService;
    private final TopologyService topologyService;

    public McpTools(QueryService queryService, Analyzer analyzer,
                    CodeIqConfig config, ObjectMapper objectMapper,
                    Optional<FlowEngine> flowEngine, GraphDatabaseService graphDb,
                    StatsService statsService, TopologyService topologyService) {
        this.queryService = queryService;
        this.analyzer = analyzer;
        this.config = config;
        this.objectMapper = objectMapper;
        this.flowEngine = flowEngine.orElse(null);
        this.graphDb = graphDb;
        this.statsService = statsService;
        this.topologyService = topologyService;
    }

    @Tool(name = "get_stats", description = "Get project graph statistics - node counts, edge counts, backend info.")
    public String getStats() {
        return toJson(queryService.getStats());
    }

    @Tool(name = "get_detailed_stats", description = "Get rich categorized statistics: frameworks, infra, connections, auth, architecture. Category: all, graph, languages, frameworks, infra, connections, auth, architecture.")
    public String getDetailedStats(
            @ToolParam(description = "Category filter (default: all)", required = false) String category) {
        try {
            java.nio.file.Path root = java.nio.file.Path.of(config.getRootPath()).toAbsolutePath().normalize();
            java.nio.file.Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
            // H2 stores data in analysis-cache.mv.db — check for that file on disk
            java.nio.file.Path h2File = root.resolve(config.getCacheDir()).resolve("analysis-cache.mv.db");

            if (!java.nio.file.Files.exists(h2File)) {
                return toJson(Map.of("error", "No analysis cache found. Run analyze first."));
            }

            List<CodeNode> nodes;
            List<CodeEdge> edges;
            try (AnalysisCache cache = new AnalysisCache(cachePath)) {
                nodes = cache.loadAllNodes();
                edges = cache.loadAllEdges();
            }

            String cat = category != null ? category : "all";
            if ("all".equalsIgnoreCase(cat)) {
                return toJson(statsService.computeStats(nodes, edges));
            }
            Map<String, Object> catStats = statsService.computeCategory(nodes, edges, cat);
            if (catStats == null) {
                return toJson(Map.of("error", "Unknown category: " + cat));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put(cat.toLowerCase(), catStats);
            return toJson(result);
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @Tool(name = "query_nodes", description = "Query nodes in the code graph. Filter by kind (endpoint, entity, guard, class, method, component, module, etc.).")
    public String queryNodes(
            @ToolParam(description = "Node kind filter", required = false) String kind,
            @ToolParam(description = "Max results", required = false) Integer limit) {
        return toJson(queryService.listNodes(kind, limit != null ? limit : 50, 0));
    }

    @Tool(name = "query_edges", description = "Query edges in the code graph. Filter by kind (calls, imports, depends_on, queries, protects, etc.).")
    public String queryEdges(
            @ToolParam(description = "Edge kind filter", required = false) String kind,
            @ToolParam(description = "Max results", required = false) Integer limit) {
        return toJson(queryService.listEdges(kind, limit != null ? limit : 50, 0));
    }

    @Tool(name = "get_node_neighbors", description = "Get all nodes connected to a given node. Direction: both, in, out.")
    public String getNodeNeighbors(
            @ToolParam(description = "Node ID") String nodeId,
            @ToolParam(description = "Direction: both, in, out", required = false) String direction) {
        return toJson(queryService.getNeighbors(nodeId, direction != null ? direction : "both"));
    }

    @Tool(name = "get_ego_graph", description = "Get the subgraph within N hops of a center node. Returns all nodes and edges in the neighborhood.")
    public String getEgoGraph(
            @ToolParam(description = "Center node ID") String center,
            @ToolParam(description = "Radius (max hops)", required = false) Integer radius) {
        return toJson(queryService.egoGraph(center, radius != null ? radius : 2));
    }

    @Tool(name = "find_cycles", description = "Find circular dependency cycles in the graph.")
    public String findCycles(
            @ToolParam(description = "Max cycles to return", required = false) Integer limit) {
        return toJson(queryService.findCycles(limit != null ? limit : 100));
    }

    @Tool(name = "find_shortest_path", description = "Find the shortest path between two nodes.")
    public String findShortestPath(
            @ToolParam(description = "Source node ID") String source,
            @ToolParam(description = "Target node ID") String target) {
        Map<String, Object> result = queryService.shortestPath(source, target);
        if (result == null) {
            return toJson(Map.of("error", "No path found between " + source + " and " + target));
        }
        return toJson(result);
    }

    @Tool(name = "find_consumers", description = "Find nodes that consume from a target (CONSUMES/LISTENS edges).")
    public String findConsumers(
            @ToolParam(description = "Target node ID") String targetId) {
        return toJson(queryService.consumersOf(targetId));
    }

    @Tool(name = "find_producers", description = "Find nodes that produce to a target (PRODUCES/PUBLISHES edges).")
    public String findProducers(
            @ToolParam(description = "Target node ID") String targetId) {
        return toJson(queryService.producersOf(targetId));
    }

    @Tool(name = "find_callers", description = "Find nodes that call a target (CALLS edges).")
    public String findCallers(
            @ToolParam(description = "Target node ID") String targetId) {
        return toJson(queryService.callersOf(targetId));
    }

    @Tool(name = "find_dependencies", description = "Find modules that a given module depends on.")
    public String findDependencies(
            @ToolParam(description = "Module node ID") String moduleId) {
        return toJson(queryService.dependenciesOf(moduleId));
    }

    @Tool(name = "find_dependents", description = "Find modules that depend on a given module.")
    public String findDependents(
            @ToolParam(description = "Module node ID") String moduleId) {
        return toJson(queryService.dependentsOf(moduleId));
    }

    @Tool(name = "generate_flow", description = "Generate an architecture flow diagram. Views: overview, ci, deploy, runtime, auth. Formats: json, mermaid.")
    public String generateFlow(
            @ToolParam(description = "View name", required = false) String view,
            @ToolParam(description = "Output format", required = false) String format) {
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

    @Tool(name = "analyze_codebase", description = "Trigger codebase analysis. Scans files, runs detectors, builds the code graph.")
    public String analyzeCodebase(
            @ToolParam(description = "Use incremental analysis", required = false) Boolean incremental) {
        try {
            boolean useIncremental = incremental != null ? incremental : true;
            AnalysisResult result = analyzer.run(Path.of(config.getRootPath()), null, useIncremental, null);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "complete");
            response.put("total_files", result.totalFiles());
            response.put("files_analyzed", result.filesAnalyzed());
            response.put("node_count", result.nodeCount());
            response.put("edge_count", result.edgeCount());
            response.put("elapsed_ms", result.elapsed().toMillis());
            return toJson(response);
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @Tool(name = "run_cypher", description = "Execute a read-only Cypher query against the Neo4j graph database.")
    public String runCypher(
            @ToolParam(description = "Cypher query string") String query) {
        // Block mutating queries
        String upper = query.trim().toUpperCase();
        if (upper.startsWith("DELETE") || upper.startsWith("REMOVE") || upper.startsWith("SET ")
                || upper.startsWith("CREATE") || upper.startsWith("MERGE") || upper.startsWith("DROP")
                || upper.contains("DETACH DELETE")
                || (upper.contains("SET ") && !upper.startsWith("MATCH"))) {
            return "{\"error\": \"Only read-only queries allowed. Mutating operations (CREATE, DELETE, SET, MERGE, DROP) are blocked.\"}";
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
                tx.commit();
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

    @Tool(name = "find_component_by_file", description = "Given a file path, find the component/module it belongs to, its layer, and all connected nodes.")
    public String findComponentByFile(
            @ToolParam(description = "File path (relative to codebase root)") String filePath) {
        return toJson(queryService.findComponentByFile(filePath));
    }

    @Tool(name = "trace_impact", description = "Trace downstream impact of a node - what depends on it, what breaks if it fails.")
    public String traceImpact(
            @ToolParam(description = "Node ID") String nodeId,
            @ToolParam(description = "Max depth", required = false) Integer depth) {
        return toJson(queryService.traceImpact(nodeId, depth != null ? depth : 3));
    }

    @Tool(name = "find_related_endpoints", description = "Given a file, class, or entity name, find all API endpoints that interact with it.")
    public String findRelatedEndpoints(
            @ToolParam(description = "File, class, or entity identifier") String identifier) {
        // Search for the identifier, then find endpoints connected to the results
        List<Map<String, Object>> results = queryService.searchGraph(identifier, 50);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("identifier", identifier);
        response.put("related_nodes", results);
        response.put("count", results.size());
        return toJson(response);
    }

    @Tool(name = "search_graph", description = "Free-text search across node labels, IDs, and properties.")
    public String searchGraph(
            @ToolParam(description = "Search query") String query,
            @ToolParam(description = "Max results", required = false) Integer limit) {
        return toJson(queryService.searchGraph(query, limit != null ? limit : 20));
    }

    @Tool(name = "read_file", description = "Read a source file from the codebase, optionally a specific line range")
    public String readFile(
            @ToolParam(description = "File path relative to codebase root") String filePath,
            @ToolParam(description = "Start line (1-based, optional)", required = false) Integer startLine,
            @ToolParam(description = "End line (1-based, inclusive, optional)", required = false) Integer endLine) {
        try {
            Path root = Path.of(config.getRootPath()).toAbsolutePath().normalize();
            Path resolved = root.resolve(filePath).normalize();
            // Path traversal protection
            if (!resolved.startsWith(root)) {
                return "Error: Path traversal detected";
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
            return "Error: " + e.getMessage();
        }
    }

    // --- Topology tools ---

    @Tool(name = "get_topology", description = "Get service topology map — all services and their connections")
    public String getTopology() {
        try {
            var data = loadCacheData();
            return toJson(topologyService.getTopology(data.nodes, data.edges));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @Tool(name = "service_detail", description = "Get detailed view of a specific service")
    public String serviceDetail(
            @ToolParam(description = "Service name") String serviceName) {
        try {
            var data = loadCacheData();
            return toJson(topologyService.serviceDetail(serviceName, data.nodes, data.edges));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @Tool(name = "service_dependencies", description = "Get dependencies of a service (databases, queues, other services)")
    public String serviceDependencies(
            @ToolParam(description = "Service name") String serviceName) {
        try {
            var data = loadCacheData();
            return toJson(topologyService.serviceDependencies(serviceName, data.nodes, data.edges));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @Tool(name = "service_dependents", description = "Get services that depend on this service")
    public String serviceDependents(
            @ToolParam(description = "Service name") String serviceName) {
        try {
            var data = loadCacheData();
            return toJson(topologyService.serviceDependents(serviceName, data.nodes, data.edges));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @Tool(name = "blast_radius", description = "Analyze blast radius — what's affected if this node changes")
    public String blastRadius(
            @ToolParam(description = "Node ID") String nodeId) {
        try {
            var data = loadCacheData();
            return toJson(topologyService.blastRadius(nodeId, data.nodes, data.edges));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @Tool(name = "find_path", description = "Find connection path between two services")
    public String findPath(
            @ToolParam(description = "Source service") String source,
            @ToolParam(description = "Target service") String target) {
        try {
            var data = loadCacheData();
            return toJson(topologyService.findPath(source, target, data.nodes, data.edges));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @Tool(name = "find_bottlenecks", description = "Find bottleneck services with most connections")
    public String findBottlenecks() {
        try {
            var data = loadCacheData();
            return toJson(topologyService.findBottlenecks(data.nodes, data.edges));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @Tool(name = "find_circular_deps", description = "Find circular service-to-service dependencies")
    public String findCircularDeps() {
        try {
            var data = loadCacheData();
            return toJson(topologyService.findCircularDeps(data.nodes, data.edges));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @Tool(name = "find_dead_services", description = "Find dead services with no incoming connections")
    public String findDeadServices() {
        try {
            var data = loadCacheData();
            return toJson(topologyService.findDeadServices(data.nodes, data.edges));
        } catch (Exception e) {
            return toJson(Map.of("error", e.getMessage()));
        }
    }

    @Tool(name = "find_node", description = "Find a node by name — exact match priority, then partial")
    public String findNode(
            @ToolParam(description = "Search query") String query) {
        try {
            var data = loadCacheData();
            return toJson(topologyService.findNode(query, data.nodes));
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
            CacheData data = loadCacheData();
            if (data.nodes().isEmpty()) return null;
            return FlowEngine.fromCache(data.nodes());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Load nodes and edges from the H2 analysis cache.
     */
    private CacheData loadCacheData() {
        Path root = Path.of(config.getRootPath()).toAbsolutePath().normalize();
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        Path h2File = root.resolve(config.getCacheDir()).resolve("analysis-cache.mv.db");

        if (!java.nio.file.Files.exists(h2File)) {
            throw new RuntimeException("No analysis cache found. Run analyze first.");
        }

        try (AnalysisCache cache = new AnalysisCache(cachePath)) {
            return new CacheData(cache.loadAllNodes(), cache.loadAllEdges());
        }
    }

    private record CacheData(java.util.List<io.github.randomcodespace.iq.model.CodeNode> nodes,
                              java.util.List<io.github.randomcodespace.iq.model.CodeEdge> edges) {}

    /**
     * Convert Neo4j node/relationship values to JSON-serializable types.
     */
    private Object toSerializable(Object val) {
        if (val == null) return null;
        if (val instanceof org.neo4j.graphdb.Node node) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("_id", node.getElementId());
            map.put("_labels", node.getLabels().spliterator().estimateSize());
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
