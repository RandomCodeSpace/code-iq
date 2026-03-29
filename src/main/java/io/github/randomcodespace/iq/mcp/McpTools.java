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
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
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
public class McpTools {

    private final QueryService queryService;
    private final Analyzer analyzer;
    private final CodeIqConfig config;
    private final ObjectMapper objectMapper;
    private final FlowEngine flowEngine;
    private final GraphDatabaseService graphDb;
    private final StatsService statsService;

    public McpTools(QueryService queryService, Analyzer analyzer,
                    CodeIqConfig config, ObjectMapper objectMapper,
                    FlowEngine flowEngine, GraphDatabaseService graphDb,
                    StatsService statsService) {
        this.queryService = queryService;
        this.analyzer = analyzer;
        this.config = config;
        this.objectMapper = objectMapper;
        this.flowEngine = flowEngine;
        this.graphDb = graphDb;
        this.statsService = statsService;
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

            if (!java.nio.file.Files.exists(cachePath)) {
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
            FlowDiagram diagram = flowEngine.generate(viewName);
            String rendered = flowEngine.render(diagram, fmt);
            return rendered;
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", e.getMessage());
            return toJson(error);
        }
    }

    @Tool(name = "analyze_codebase", description = "Trigger codebase analysis. Scans files, runs detectors, builds the code graph.")
    public String analyzeCosdebase(
            @ToolParam(description = "Use incremental analysis", required = false) Boolean incremental) {
        try {
            AnalysisResult result = analyzer.run(Path.of(config.getRootPath()), null);
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

    @Tool(name = "run_cypher", description = "Execute a raw Cypher query against the Neo4j graph database.")
    public String runCypher(
            @ToolParam(description = "Cypher query string") String query) {
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

    @Tool(name = "read_file", description = "Read a source file's content for deep analysis. Path is relative to the codebase root.")
    public String readFile(
            @ToolParam(description = "File path relative to codebase root") String filePath) {
        try {
            Path root = Path.of(config.getRootPath()).toAbsolutePath().normalize();
            Path resolved = root.resolve(filePath).normalize();
            // Path traversal protection
            if (!resolved.startsWith(root)) {
                return "Error: Path traversal detected";
            }
            return java.nio.file.Files.readString(resolved, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

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
