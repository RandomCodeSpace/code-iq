package io.github.randomcodespace.iq.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePackAssembler;
import io.github.randomcodespace.iq.intelligence.evidence.EvidencePackRequest;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadata;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadataProvider;
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

/**
 * MCP tool definitions using Spring AI annotations.
 * Tool names match the Python MCP implementation exactly.
 */
@Component
@Profile("serving")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "codeiq.neo4j.enabled", havingValue = "true", matchIfMissing = true)
public class McpTools {
    private static final String PROP_ERROR = "error";


    private final QueryService queryService;
    private final CodeIqConfig config;
    private final ObjectMapper objectMapper;
    private final FlowEngine flowEngine;
    private final GraphDatabaseService graphDb;
    private final StatsService statsService;
    private final TopologyService topologyService;
    private final GraphStore graphStore;
    private final EvidencePackAssembler evidencePackAssembler;
    private final ArtifactMetadataProvider artifactMetadataProvider;

    public McpTools(QueryService queryService,
                    CodeIqConfig config, ObjectMapper objectMapper,
                    Optional<FlowEngine> flowEngine, GraphDatabaseService graphDb,
                    StatsService statsService, TopologyService topologyService,
                    GraphStore graphStore,
                    Optional<EvidencePackAssembler> evidencePackAssembler,
                    Optional<ArtifactMetadataProvider> artifactMetadataProvider) {
        this.queryService = queryService;
        this.config = config;
        this.objectMapper = objectMapper;
        this.flowEngine = flowEngine.orElse(null);
        this.graphDb = graphDb;
        this.statsService = statsService;
        this.topologyService = topologyService;
        this.graphStore = graphStore;
        this.evidencePackAssembler = evidencePackAssembler.orElse(null);
        this.artifactMetadataProvider = artifactMetadataProvider.orElse(null);
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
            throw new RuntimeException("No analysis data available. Run 'codeiq analyze' first.");
        }
        return new CacheData(nodes, edges);
    }

    @McpTool(name = "get_stats", description = "Get graph overview: total nodes, edges, files, languages, and frameworks detected. Use when asked about project size, composition, or what was analyzed. Returns JSON with counts and breakdowns.")
    public String getStats() {
        try {
            return toJson(queryService.getStats());
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "get_detailed_stats", description = "Get categorized statistics: graph metrics, language distribution, framework usage, infrastructure, API connections, auth patterns, and architecture layers. Use for deep project analysis. Filter by category: graph, languages, frameworks, infra, connections, auth, architecture, or all.")
    public String getDetailedStats(
            @McpToolParam(description = "Category filter (default: all)", required = false) String category) {
        try {
            return toJson(queryService.getDetailedStats(category != null ? category : "all"));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "query_nodes", description = "List nodes in the knowledge graph filtered by kind. Kinds: endpoint, entity, class, method, guard, service, module, topic, queue, config_file, database_connection, component, etc. Use when asked 'show me all endpoints' or 'what entities exist'. Returns paginated node list with IDs, labels, and properties.")
    public String queryNodes(
            @McpToolParam(description = "Node kind to filter by: endpoint, entity, class, method, guard, service, module, topic, queue, config_file, database_connection, component, interface, enum, etc.", required = false) String kind,
            @McpToolParam(description = "Maximum number of results to return (default: 50)", required = false) Integer limit) {
        try {
            return toJson(queryService.listNodes(kind, limit != null ? limit : 50, 0));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "query_edges", description = "List edges (relationships) in the graph filtered by kind. Kinds: calls, imports, depends_on, queries, produces, consumes, protects, extends, contains, connects_to, etc. Use when asked 'what calls what' or 'show all dependencies'. Returns paginated edge list.")
    public String queryEdges(
            @McpToolParam(description = "Edge kind to filter by: calls, imports, depends_on, queries, produces, consumes, protects, extends, implements, contains, connects_to, maps_to, etc.", required = false) String kind,
            @McpToolParam(description = "Maximum number of results to return (default: 50)", required = false) Integer limit) {
        try {
            return toJson(queryService.listEdges(kind, limit != null ? limit : 50, 0));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "get_node_neighbors", description = "Get all nodes directly connected to a given node, with direction control (inbound, outbound, or both). Use when asked 'what connects to this service?' or 'what does this class depend on?'. Returns neighbor nodes grouped by edge kind and direction.")
    public String getNodeNeighbors(
            @McpToolParam(description = "Node ID") String nodeId,
            @McpToolParam(description = "Relationship direction: 'in' (who points to this node), 'out' (what this node points to), or 'both' (default)", required = false) String direction) {
        try {
            return toJson(queryService.getNeighbors(nodeId, direction != null ? direction : "both"));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "get_ego_graph", description = "Get the full subgraph within N hops of a center node — all reachable nodes and edges. Use for exploring the neighborhood of a component, understanding local architecture, or visualizing a module's context. Returns nodes and edges as a graph structure.")
    public String getEgoGraph(
            @McpToolParam(description = "Center node ID") String center,
            @McpToolParam(description = "Number of hops from center node (default: 2, max: 10)", required = false) Integer radius) {
        try {
            return toJson(queryService.egoGraph(center, radius != null ? radius : 2));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_cycles", description = "Detect circular dependency cycles in the graph. Use when asked about circular dependencies, architecture violations, or import loops. Returns list of cycles as ordered node ID paths.")
    public String findCycles(
            @McpToolParam(description = "Maximum number of cycles to return (default: 100)", required = false) Integer limit) {
        try {
            return toJson(queryService.findCycles(limit != null ? limit : 100));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_shortest_path", description = "Find the shortest relationship path between two nodes. Use when asked 'how is A connected to B?' or 'what's the dependency chain from X to Y?'. Returns ordered list of nodes and edges along the path.")
    public String findShortestPath(
            @McpToolParam(description = "Source node ID") String source,
            @McpToolParam(description = "Target node ID") String target) {
        try {
            Map<String, Object> result = queryService.shortestPath(source, target);
            if (result == null) {
                return toJson(Map.of(PROP_ERROR, "No path found between " + source + " and " + target));
            }
            return toJson(result);
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_consumers", description = "Find all services, handlers, or functions that consume/listen from a given topic, queue, or event source. Use when asked 'what reads from this topic?' or 'who listens to this event?'. Returns consumer nodes with their kind, label, and file location.")
    public String findConsumers(
            @McpToolParam(description = "Target node ID") String targetId) {
        try {
            return toJson(queryService.consumersOf(targetId));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_producers", description = "Find all services or functions that produce/publish to a given topic, queue, or event target. Use when asked 'what writes to this topic?' or 'who publishes to this queue?'. Returns producer nodes with details.")
    public String findProducers(
            @McpToolParam(description = "Target node ID") String targetId) {
        try {
            return toJson(queryService.producersOf(targetId));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_callers", description = "Find all methods or services that call a given target function, method, or service. Use when asked 'who calls this method?' or 'what invokes this service?'. Returns caller nodes with edge details.")
    public String findCallers(
            @McpToolParam(description = "Target node ID") String targetId) {
        try {
            return toJson(queryService.callersOf(targetId));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_dependencies", description = "Find all modules, services, or packages that a given module depends on (outbound dependencies). Use when asked 'what does this service depend on?' or 'show me the dependency tree'. Returns dependency nodes.")
    public String findDependencies(
            @McpToolParam(description = "Module node ID") String moduleId) {
        try {
            return toJson(queryService.dependenciesOf(moduleId));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_dependents", description = "Find all modules or services that depend on a given module (inbound — who uses it). Use when asked 'what breaks if I change this?' or 'who depends on this library?'. Returns dependent nodes.")
    public String findDependents(
            @McpToolParam(description = "Module node ID") String moduleId) {
        try {
            return toJson(queryService.dependentsOf(moduleId));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_dead_code", description = "Find potentially unreachable code: classes, methods, or interfaces with no incoming calls, imports, or references. Use when asked about unused code, cleanup candidates, or dead code analysis. Filter by kind (class, method, interface). Returns nodes that appear isolated.")
    public String findDeadCode(
            @McpToolParam(description = "Filter by node kind: class, method, interface, or omit for all", required = false) String kind,
            @McpToolParam(description = "Maximum results (default: 100, max: 1000)", required = false) Integer limit) {
        try {
            int safeLimit = limit != null ? Math.min(limit, 1000) : 100;
            return toJson(queryService.findDeadCode(kind, safeLimit));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "generate_flow", description = "Generate an architecture flow diagram for the codebase. Views: overview (full system), ci (build pipeline), deploy (deployment topology), runtime (service communication), auth (security flow). Output as JSON graph or Mermaid markdown.")
    public String generateFlow(
            @McpToolParam(description = "View name: overview, ci, deploy, runtime, or auth (default: overview)", required = false) String view,
            @McpToolParam(description = "Output format: json or mermaid (default: json)", required = false) String format) {
        String viewName = view != null ? view : "overview";
        String fmt = format != null ? format : "json";
        try {
            FlowEngine engine = resolveFlowEngine();
            if (engine == null) {
                return toJson(Map.of(PROP_ERROR, "No analysis data available. Run 'codeiq analyze' first."));
            }
            FlowDiagram diagram = engine.generate(viewName);
            String rendered = engine.render(diagram, fmt);
            return rendered;
        } catch (IllegalArgumentException e) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put(PROP_ERROR, e.getMessage());
            return toJson(error);
        }
    }

    // analyze_codebase removed — MCP server runs on remote hosts where
    // source code is not available (only the bundled graph). Analysis is
    // done locally via CLI: codeiq analyze / codeiq index

    @McpTool(name = "run_cypher", description = "Execute a custom read-only Cypher query directly against the Neo4j graph. Use for advanced queries not covered by other tools. CALL db.* procedures are allowed (fulltext search, schema inspection). Mutation queries are blocked. Returns rows as JSON array.")
    public String runCypher(
            @McpToolParam(description = "Read-only Cypher query. MATCH, RETURN, WITH, WHERE, CALL db.* allowed. CREATE, DELETE, SET, MERGE blocked.") String query) {
        // Block mutation keywords (defense-in-depth). Uses case-insensitive matching
        // so the original query casing is preserved for Neo4j execution.
        // CALL db.* is explicitly allowed (read-only: fulltext search, schema, indexes).
        String trimmed = query.trim();
        List<java.util.regex.Pattern> BLOCKED_PATTERNS = List.of(
                java.util.regex.Pattern.compile("\\bCREATE\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("\\bDELETE\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("\\bDETACH\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("\\bSET\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("\\bREMOVE\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("\\bMERGE\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("\\bDROP\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("\\bFOREACH\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
                java.util.regex.Pattern.compile("\\bLOAD\\s+CSV\\b", java.util.regex.Pattern.CASE_INSENSITIVE),
                // Allow CALL db.* (read-only procedures: indexes, schema, fulltext search)
                // Block all other CALL forms (mutation procedures like apoc.create, apoc.merge)
                java.util.regex.Pattern.compile("\\bCALL\\s+(?!db\\.)", java.util.regex.Pattern.CASE_INSENSITIVE));
        for (var pattern : BLOCKED_PATTERNS) {
            if (pattern.matcher(trimmed).find()) {
                return toJson(Map.of(PROP_ERROR, "Read-only queries only. Mutation keyword found: " + pattern.pattern()));
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
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    // --- Agentic triage tools ---

    @McpTool(name = "find_component_by_file", description = "Given a source file path, find which module/service it belongs to, its architecture layer (frontend/backend/infra), and all nodes defined in that file. Use when asked 'what component is this file part of?' or for file-level triage.")
    public String findComponentByFile(
            @McpToolParam(description = "File path (relative to codebase root)") String filePath) {
        try {
            return toJson(queryService.findComponentByFile(filePath));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "trace_impact", description = "Trace the downstream blast radius of a node — everything that depends on it, transitively up to N hops. Use when asked 'what breaks if I change this?' or 'what's the impact of modifying this service?'. Returns affected nodes grouped by depth.")
    public String traceImpact(
            @McpToolParam(description = "Node ID") String nodeId,
            @McpToolParam(description = "Maximum traversal depth (default: 3, max: 10)", required = false) Integer depth) {
        try {
            return toJson(queryService.traceImpact(nodeId, depth != null ? depth : 3));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_related_endpoints", description = "Given a file, class, or entity name, find all REST/gRPC/GraphQL endpoints that interact with it. Use when asked 'which APIs use this entity?' or 'what endpoints touch the User table?'. Returns endpoint nodes with HTTP methods and paths.")
    public String findRelatedEndpoints(
            @McpToolParam(description = "File, class, or entity identifier") String identifier) {
        try {
            return toJson(queryService.findRelatedEndpoints(identifier));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "search_graph", description = "Full-text search across all node labels, IDs, file paths, and properties. Use as the starting point when the user mentions a name but you don't have the exact node ID. Returns matching nodes ranked by relevance.")
    public String searchGraph(
            @McpToolParam(description = "Search query") String query,
            @McpToolParam(description = "Maximum results (default: 20)", required = false) Integer limit) {
        try {
            return toJson(queryService.searchGraph(query, limit != null ? limit : 20));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "get_capabilities", description = "Show the analysis capability matrix: what Code IQ can detect per language (Java, Python, TypeScript, Go, etc.) across dimensions like call graph, type hierarchy, framework detection. Levels: EXACT, PARTIAL, LEXICAL_ONLY, UNSUPPORTED. Use when asked 'what languages do you support?' or 'how accurate is the analysis?'.")
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
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "read_file", description = "Read source file content from the analyzed codebase. Supports full file or line range. Use when you need to show actual code to the user, verify a detection result, or provide code context. Returns raw file content as text.")
    public String readFile(
            @McpToolParam(description = "File path relative to the codebase root (e.g., src/main/java/com/example/UserService.java)") String filePath,
            @McpToolParam(description = "Start line number, 1-based (optional — omit to read entire file)", required = false) Integer startLine,
            @McpToolParam(description = "End line number, 1-based inclusive (optional — omit to read to end)", required = false) Integer endLine) {
        try {
            Path root = Path.of(config.getRootPath()).toRealPath();
            Path candidate = root.resolve(filePath).normalize();
            // Lexical traversal guard (rejects ../ before any filesystem touch)
            if (!candidate.startsWith(root)) {
                return toJson(Map.of(PROP_ERROR, "Path traversal detected"));
            }
            // Follow symlinks and re-check so an in-repo symlink pointing outside the
            // codebase (e.g. link -> /etc/passwd) cannot be used to exfiltrate files.
            Path resolved = candidate.toRealPath();
            if (!resolved.startsWith(root)) {
                return toJson(Map.of(PROP_ERROR, "Path traversal detected"));
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
            return toJson(Map.of(PROP_ERROR, "Failed to read file: " + e.getMessage()));
        }
    }

    // --- Topology tools ---

    @McpTool(name = "get_topology", description = "Get the service topology map: all services, infrastructure nodes (databases, message queues, caches), and runtime connections between them. Use when asked about service architecture, system overview, or 'how do services communicate?'. Returns services with connection counts and infrastructure details.")
    public String getTopology() {
        try {
            return toJson(queryService.getTopology());
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "service_detail", description = "Get comprehensive details about a specific service: its endpoints, entities, dependencies, dependents, guards, infrastructure connections, and node counts by kind. Use when asked 'tell me about the order-service' or for deep-diving into one service.")
    public String serviceDetail(
            @McpToolParam(description = "Service name") String serviceName) {
        try {
            var data = getCachedData();
            return toJson(topologyService.serviceDetail(serviceName, data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "service_dependencies", description = "List everything a service depends on: databases it queries, queues it produces to, other services it calls, caches it uses. Use when asked 'what does this service need to run?' or 'what are its downstream dependencies?'.")
    public String serviceDependencies(
            @McpToolParam(description = "Service name") String serviceName) {
        try {
            var data = getCachedData();
            return toJson(topologyService.serviceDependencies(serviceName, data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "service_dependents", description = "List all services and components that depend on this service — its upstream consumers. Use when asked 'who calls this service?' or 'what breaks if this service goes down?'.")
    public String serviceDependents(
            @McpToolParam(description = "Service name") String serviceName) {
        try {
            var data = getCachedData();
            return toJson(topologyService.serviceDependents(serviceName, data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "blast_radius", description = "Analyze the blast radius of a node: all nodes affected if it changes, grouped by hop distance. Use for change impact analysis, incident triage, or understanding coupling. Returns affected nodes with paths showing how they're connected.")
    public String blastRadius(
            @McpToolParam(description = "Node ID") String nodeId) {
        try {
            var data = getCachedData();
            return toJson(topologyService.blastRadius(nodeId, data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_path", description = "Find the connection path between two services in the topology. Use when asked 'how does service A talk to service B?' or 'what's the chain between frontend and database?'. Returns the ordered path of services and connections.")
    public String findPath(
            @McpToolParam(description = "Source service") String source,
            @McpToolParam(description = "Target service") String target) {
        try {
            var data = getCachedData();
            return toJson(topologyService.findPath(source, target, data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_bottlenecks", description = "Identify bottleneck services with the most inbound and outbound connections — high-traffic hubs that are potential single points of failure. Use when asked about architecture risks, scaling concerns, or 'which services are most critical?'.")
    public String findBottlenecks() {
        try {
            var data = getCachedData();
            return toJson(topologyService.findBottlenecks(data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_circular_deps", description = "Detect circular dependencies between services (A->B->C->A). Use when asked about architecture health, deployment order issues, or 'are there any circular service dependencies?'. Returns cycles as ordered service name lists.")
    public String findCircularDeps() {
        try {
            var data = getCachedData();
            return toJson(topologyService.findCircularDeps(data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_dead_services", description = "Find services with zero incoming connections — potentially unused or orphaned services. Use when asked about cleanup opportunities or 'are there any services nothing calls?'. Returns isolated service nodes.")
    public String findDeadServices() {
        try {
            var data = getCachedData();
            return toJson(topologyService.findDeadServices(data.nodes(), data.edges()));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "find_node", description = "Find a node by name with fuzzy matching — exact match priority, then partial/contains match. Use as a quick lookup when you have a name but not the full node ID. Returns best-matching node with its properties and connections.")
    public String findNode(
            @McpToolParam(description = "Search query") String query) {
        try {
            var data = getCachedData();
            return toJson(topologyService.findNode(query, data.nodes()));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "get_evidence_pack", description = "Assemble a comprehensive evidence pack for a symbol (class, method, function) or file: matched graph nodes, source code snippets, provenance metadata, analysis confidence level, and any degradation notes. Use when asked to explain or investigate a specific code element in depth.")
    public String getEvidencePack(
            @McpToolParam(description = "Symbol name to look up (e.g. UserService, handleLogin)", required = false) String symbol,
            @McpToolParam(description = "File path relative to repo root", required = false) String filePath,
            @McpToolParam(description = "Max lines per snippet (default: config value)", required = false) Integer maxSnippetLines,
            @McpToolParam(description = "Include cross-reference nodes (default: false)", required = false) Boolean includeReferences) {
        if (evidencePackAssembler == null) {
            return toJson(Map.of(PROP_ERROR, "Evidence pack service unavailable. Run 'enrich' first."));
        }
        try {
            EvidencePackRequest request = new EvidencePackRequest(
                    symbol, filePath, maxSnippetLines,
                    Boolean.TRUE.equals(includeReferences));
            return toJson(evidencePackAssembler.assemble(request, currentArtifactMetadata()));
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
        }
    }

    @McpTool(name = "get_artifact_metadata", description = "Return provenance metadata about the analyzed codebase: repository identity, commit SHA, build timestamp, analysis tool versions, capability matrix snapshot, and integrity hash. Use when asked about analysis freshness, data provenance, or 'when was this last scanned?'.")
    public String getArtifactMetadata() {
        ArtifactMetadata artifactMetadata = currentArtifactMetadata();
        if (artifactMetadata == null) {
            return toJson(Map.of(PROP_ERROR, "Artifact metadata unavailable. Run 'enrich' first."));
        }
        try {
            return toJson(artifactMetadata);
        } catch (Exception e) {
            return toJson(Map.of(PROP_ERROR, e.getMessage()));
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

    private ArtifactMetadata currentArtifactMetadata() {
        return artifactMetadataProvider != null ? artifactMetadataProvider.current() : null;
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
