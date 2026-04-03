package io.github.randomcodespace.iq.query;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * High-level query service wrapping GraphStore with caching.
 * All methods return simple Map/List structures for JSON serialization.
 */
@Service
@ConditionalOnBean(GraphStore.class)
public class QueryService {

    private final GraphStore graphStore;
    private final CodeIqConfig config;

    public QueryService(GraphStore graphStore, CodeIqConfig config) {
        this.graphStore = graphStore;
        this.config = config;
    }

    @Cacheable("graph-stats")
    public Map<String, Object> getStats() {
        // Use Cypher aggregation — never loads full nodes into heap
        Map<String, Object> result = graphStore.computeAggregateStats();

        // Also include raw counts and breakdowns for backward compat
        Map<String, Long> nodesByKind = new LinkedHashMap<>();
        for (Map<String, Object> row : graphStore.countNodesByKind()) {
            nodesByKind.put((String) row.get("kind"), ((Number) row.get("cnt")).longValue());
        }
        Map<String, Long> nodesByLayer = new LinkedHashMap<>();
        for (Map<String, Object> row : graphStore.countNodesByLayer()) {
            nodesByLayer.put((String) row.get("layer"), ((Number) row.get("cnt")).longValue());
        }

        // Read from already-computed graph sub-map instead of re-querying
        @SuppressWarnings("unchecked")
        Map<String, Object> graphStats = (Map<String, Object>) result.get("graph");
        if (graphStats != null) {
            result.put("node_count", graphStats.get("nodes"));
            result.put("edge_count", graphStats.get("edges"));
        } else {
            result.put("node_count", graphStore.count());
            result.put("edge_count", graphStore.countEdges());
        }
        result.put("nodes_by_kind", nodesByKind);
        result.put("nodes_by_layer", nodesByLayer);
        return result;
    }

    /**
     * Get detailed stats for a specific category, or all categories.
     * Categories: all, graph, languages, frameworks, infra, connections, auth, architecture
     */
    @Cacheable(value = "detailed-stats", key = "#category")
    public Map<String, Object> getDetailedStats(String category) {
        // Use Cypher aggregation — never loads full nodes into heap
        if (category == null || "all".equalsIgnoreCase(category)) {
            return graphStore.computeAggregateStats();
        }
        Map<String, Object> catResult = graphStore.computeAggregateCategoryStats(category);
        if (catResult == null) {
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("error", "Unknown category: " + category
                    + ". Valid: all, graph, languages, frameworks, infra, connections, auth, architecture");
            return error;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(category.toLowerCase(), catResult);
        return result;
    }

    @Cacheable("kinds-list")
    public Map<String, Object> listKinds() {
        List<Map<String, Object>> rawCounts = graphStore.countNodesByKind();

        List<Map<String, Object>> kinds = new ArrayList<>();
        rawCounts.stream()
                .sorted((a, b) -> Long.compare(
                        ((Number) b.get("cnt")).longValue(),
                        ((Number) a.get("cnt")).longValue()))
                .forEach(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", row.get("kind"));
                    m.put("count", ((Number) row.get("cnt")).longValue());
                    kinds.add(m);
                });
        long totalNodes = rawCounts.stream()
                .mapToLong(r -> ((Number) r.get("cnt")).longValue())
                .sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kinds", kinds);
        result.put("total", totalNodes);
        return result;
    }

    @Cacheable(value = "kind-nodes", key = "#kind + ':' + #offset + ':' + #limit")
    public Map<String, Object> nodesByKind(String kind, int limit, int offset) {
        List<CodeNode> nodes = graphStore.findByKindPaginated(kind, offset, limit);
        long total = graphStore.countByKind(kind);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind);
        result.put("total", total);
        result.put("offset", offset);
        result.put("limit", limit);
        result.put("nodes", nodes.stream().map(this::nodeToMap).toList());
        return result;
    }

    public Map<String, Object> listNodes(String kind, int limit, int offset) {
        List<CodeNode> nodes;
        if (kind != null && !kind.isBlank()) {
            nodes = graphStore.findByKindPaginated(kind, offset, limit);
        } else {
            nodes = graphStore.findAllPaginated(offset, limit);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", nodes.stream().map(this::nodeToMap).toList());
        result.put("count", nodes.size());
        result.put("offset", offset);
        result.put("limit", limit);
        return result;
    }

    public Map<String, Object> listEdges(String kind, int limit, int offset) {
        List<Map<String, Object>> rawEdges;
        long total;
        if (kind != null && !kind.isBlank()) {
            rawEdges = graphStore.findEdgesByKindPaginated(kind, offset, limit);
            total = graphStore.countEdgesByKind(kind);
        } else {
            rawEdges = graphStore.findEdgesPaginated(offset, limit);
            total = graphStore.countEdges();
        }

        List<Map<String, Object>> edges = rawEdges.stream().map(row -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", row.get("id"));
            m.put("kind", row.get("kind"));
            m.put("source", row.get("sourceId"));
            m.put("target", row.get("targetId"));
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("edges", edges);
        result.put("count", edges.size());
        result.put("total", total);
        return result;
    }

    @Cacheable(value = "node-detail", key = "#nodeId")
    public Map<String, Object> nodeDetailWithEdges(String nodeId) {
        return graphStore.findById(nodeId)
                .map(node -> {
                    Map<String, Object> detail = nodeToMap(node);
                    detail.put("outgoing_edges", node.getEdges().stream()
                            .map(this::edgeToMap)
                            .toList());

                    List<CodeNode> incoming = graphStore.findIncomingNeighbors(nodeId);
                    detail.put("incoming_nodes", incoming.stream()
                            .map(this::nodeToMap)
                            .toList());
                    return detail;
                })
                .orElse(null);
    }

    public Map<String, Object> getNeighbors(String nodeId, String direction) {
        List<CodeNode> neighbors = switch (direction) {
            case "out" -> graphStore.findOutgoingNeighbors(nodeId);
            case "in" -> graphStore.findIncomingNeighbors(nodeId);
            default -> graphStore.findNeighbors(nodeId);
        };

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_id", nodeId);
        result.put("direction", direction);
        result.put("neighbors", neighbors.stream().map(this::nodeToMap).toList());
        result.put("count", neighbors.size());
        return result;
    }

    // --- Graph traversal queries ---

    public Map<String, Object> shortestPath(String source, String target) {
        List<String> path = graphStore.findShortestPath(source, target);
        if (path == null || path.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", source);
        result.put("target", target);
        result.put("path", path);
        result.put("length", path.size() - 1);
        return result;
    }

    public Map<String, Object> findCycles(int limit) {
        int cappedLimit = Math.min(limit, 1000);
        List<List<String>> cycles = graphStore.findCycles(cappedLimit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cycles", cycles);
        result.put("count", cycles.size());
        return result;
    }

    @Cacheable(value = "impact-trace", key = "#nodeId + ':' + #depth")
    public Map<String, Object> traceImpact(String nodeId, int depth) {
        int cappedDepth = Math.min(depth, config.getMaxDepth());
        List<CodeNode> impacted = graphStore.traceImpact(nodeId, cappedDepth);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", nodeId);
        result.put("depth", cappedDepth);
        result.put("impacted", impacted.stream().map(this::nodeToMap).toList());
        result.put("count", impacted.size());
        return result;
    }

    public Map<String, Object> egoGraph(String center, int radius) {
        int cappedRadius = Math.min(radius, config.getMaxRadius());
        List<CodeNode> nodes = new ArrayList<>(graphStore.findEgoGraph(center, cappedRadius));

        // Include center node
        graphStore.findById(center).ifPresent(c -> {
            if (!nodes.contains(c)) {
                nodes.addFirst(c);
            }
        });

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("center", center);
        result.put("radius", cappedRadius);
        result.put("nodes", nodes.stream().map(this::nodeToMap).toList());
        result.put("count", nodes.size());
        return result;
    }

    // --- Relationship queries ---

    public Map<String, Object> consumersOf(String targetId) {
        List<CodeNode> consumers = graphStore.findConsumers(targetId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", targetId);
        result.put("consumers", consumers.stream().map(this::nodeToMap).toList());
        result.put("count", consumers.size());
        return result;
    }

    public Map<String, Object> producersOf(String targetId) {
        List<CodeNode> producers = graphStore.findProducers(targetId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", targetId);
        result.put("producers", producers.stream().map(this::nodeToMap).toList());
        result.put("count", producers.size());
        return result;
    }

    public Map<String, Object> callersOf(String targetId) {
        List<CodeNode> callers = graphStore.findCallers(targetId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", targetId);
        result.put("callers", callers.stream().map(this::nodeToMap).toList());
        result.put("count", callers.size());
        return result;
    }

    public Map<String, Object> dependenciesOf(String moduleId) {
        List<CodeNode> deps = graphStore.findDependencies(moduleId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("module", moduleId);
        result.put("dependencies", deps.stream().map(this::nodeToMap).toList());
        result.put("count", deps.size());
        return result;
    }

    public Map<String, Object> dependentsOf(String moduleId) {
        List<CodeNode> deps = graphStore.findDependents(moduleId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("module", moduleId);
        result.put("dependents", deps.stream().map(this::nodeToMap).toList());
        result.put("count", deps.size());
        return result;
    }

    // --- Triage queries ---

    public Map<String, Object> findComponentByFile(String filePath) {
        List<CodeNode> nodes = graphStore.findByFilePath(filePath);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", filePath);
        result.put("nodes", nodes.stream().map(this::nodeToMap).toList());
        result.put("count", nodes.size());

        if (!nodes.isEmpty()) {
            CodeNode first = nodes.getFirst();
            result.put("module", first.getModule());
            result.put("layer", first.getLayer());
        }
        return result;
    }

    @Cacheable(value = "search-results", key = "#query + ':' + #limit")
    public List<Map<String, Object>> searchGraph(String query, int limit) {
        int cappedLimit = Math.min(limit, 200);
        List<CodeNode> results = graphStore.search(query, cappedLimit);
        return results.stream().map(this::nodeToMap).toList();
    }

    /**
     * Find API endpoints related to an identifier (file, class, entity).
     * Searches for matching nodes, then traverses the graph to find connected endpoints.
     */
    public Map<String, Object> findRelatedEndpoints(String identifier) {
        // Find nodes matching the identifier
        List<CodeNode> matches = graphStore.search(identifier, 50);

        // Collect endpoints: any match that IS an endpoint, plus neighbors of matches that are endpoints
        Set<String> seenIds = new java.util.LinkedHashSet<>();
        List<Map<String, Object>> endpoints = new ArrayList<>();

        // First pass: collect matches that are themselves endpoints
        for (CodeNode match : matches) {
            if (match.getKind() == NodeKind.ENDPOINT || match.getKind() == NodeKind.WEBSOCKET_ENDPOINT) {
                if (seenIds.add(match.getId())) {
                    endpoints.add(nodeToMap(match));
                }
            }
        }

        // Single batched query for all endpoint neighbors (replaces N+1 loop)
        List<String> matchIds = matches.stream().map(CodeNode::getId).toList();
        Map<String, List<CodeNode>> endpointNeighbors = graphStore.findEndpointNeighborsBatch(matchIds);
        for (Map.Entry<String, List<CodeNode>> entry : endpointNeighbors.entrySet()) {
            String sourceId = entry.getKey();
            for (CodeNode neighbor : entry.getValue()) {
                if (seenIds.add(neighbor.getId())) {
                    Map<String, Object> epMap = nodeToMap(neighbor);
                    epMap.put("connected_via", sourceId);
                    endpoints.add(epMap);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("identifier", identifier);
        result.put("endpoints", endpoints);
        result.put("count", endpoints.size());
        result.put("searched_nodes", matches.size());
        return result;
    }

    // --- Topology ---

    @Cacheable("topology")
    public Map<String, Object> getTopology() {
        return graphStore.getTopology();
    }

    // --- Dead code detection ---

    /**
     * Semantic edge kinds that count as "usage" — if a node has no incoming edge
     * of any of these kinds, it is considered potentially dead code.
     * Structural edges like contains, defines, configures are excluded because
     * they are always present from parent modules/config files.
     */
    private static final List<String> SEMANTIC_EDGE_KINDS = List.of(
            "calls", "imports", "depends_on", "uses", "extends", "implements",
            "injects", "queries", "maps_to", "consumes", "listens",
            "invokes_rmi", "overrides", "connects_to", "triggers", "renders");

    /**
     * Node kinds that are entry points — they are intended to have no callers
     * and should not be flagged as dead code.
     */
    private static final List<String> ENTRY_POINT_KINDS = List.of(
            NodeKind.ENDPOINT.getValue(),
            NodeKind.WEBSOCKET_ENDPOINT.getValue(),
            NodeKind.MIGRATION.getValue(),
            NodeKind.CONFIG_FILE.getValue(),
            NodeKind.CONFIG_KEY.getValue(),
            NodeKind.CONFIG_DEFINITION.getValue());

    @Cacheable(value = "dead-code", key = "#kind + ':' + #limit")
    public Map<String, Object> findDeadCode(String kind, int limit) {
        List<String> kinds;
        if (kind != null && !kind.isBlank()) {
            kinds = List.of(kind);
        } else {
            kinds = List.of(
                    NodeKind.CLASS.getValue(),
                    NodeKind.METHOD.getValue(),
                    NodeKind.INTERFACE.getValue(),
                    NodeKind.ABSTRACT_CLASS.getValue(),
                    NodeKind.COMPONENT.getValue(),
                    NodeKind.SERVICE.getValue());
        }

        List<CodeNode> deadNodes = graphStore.findNodesWithoutIncomingSemantic(
                kinds, SEMANTIC_EDGE_KINDS, ENTRY_POINT_KINDS, 0, limit);

        List<Map<String, Object>> deadCode = deadNodes.stream()
                .map(n -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", n.getId());
                    m.put("kind", n.getKind().getValue());
                    m.put("label", n.getLabel());
                    m.put("file", n.getFilePath());
                    return m;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dead_code", deadCode);
        result.put("count", deadCode.size());
        return result;
    }

    // --- Serialization helpers ---

    Map<String, Object> nodeToMap(CodeNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", node.getId());
        m.put("kind", node.getKind().getValue());
        m.put("label", node.getLabel());
        if (node.getFqn() != null) m.put("fqn", node.getFqn());
        if (node.getModule() != null) m.put("module", node.getModule());
        if (node.getFilePath() != null) m.put("file_path", node.getFilePath());
        if (node.getLineStart() != null) m.put("line_start", node.getLineStart());
        if (node.getLineEnd() != null) m.put("line_end", node.getLineEnd());
        if (node.getLayer() != null) m.put("layer", node.getLayer());
        if (node.getAnnotations() != null && !node.getAnnotations().isEmpty()) {
            m.put("annotations", node.getAnnotations());
        }
        if (node.getProperties() != null && !node.getProperties().isEmpty()) {
            m.put("properties", node.getProperties());
        }
        return m;
    }

    private Map<String, Object> edgeToMap(CodeEdge edge) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", edge.getId());
        m.put("kind", edge.getKind().getValue());
        m.put("source", edge.getSourceId());
        if (edge.getTarget() != null) {
            m.put("target", edge.getTarget().getId());
            m.put("target_label", edge.getTarget().getLabel());
            m.put("target_kind", edge.getTarget().getKind().getValue());
        }
        if (edge.getProperties() != null && !edge.getProperties().isEmpty()) {
            m.put("properties", edge.getProperties());
        }
        return m;
    }
}
