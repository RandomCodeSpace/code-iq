package io.github.randomcodespace.iq.query;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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

    // TODO: Replace findAll() with Cypher aggregation queries for node/edge counts
    //       to avoid loading entire graph into memory. Requires Neo4j to be running.
    @Cacheable("graph-stats")
    public Map<String, Object> getStats() {
        long nodeCount = graphStore.count();
        List<CodeNode> allNodes = graphStore.findAll();
        long edgeCount = allNodes.stream()
                .mapToLong(n -> n.getEdges().size())
                .sum();

        Map<String, Long> nodesByKind = allNodes.stream()
                .collect(Collectors.groupingBy(
                        n -> n.getKind().getValue(),
                        Collectors.counting()));

        Map<String, Long> nodesByLayer = allNodes.stream()
                .filter(n -> n.getLayer() != null)
                .collect(Collectors.groupingBy(
                        CodeNode::getLayer,
                        Collectors.counting()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_count", nodeCount);
        result.put("edge_count", edgeCount);
        result.put("nodes_by_kind", nodesByKind);
        result.put("nodes_by_layer", nodesByLayer);
        return result;
    }

    @Cacheable("kinds-list")
    public Map<String, Object> listKinds() {
        List<CodeNode> allNodes = graphStore.findAll();
        Map<String, Long> kindCounts = allNodes.stream()
                .collect(Collectors.groupingBy(
                        n -> n.getKind().getValue(),
                        Collectors.counting()));

        List<Map<String, Object>> kinds = kindCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("kind", e.getKey());
                    m.put("count", e.getValue());
                    return m;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kinds", kinds);
        result.put("total", allNodes.size());
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
        List<CodeNode> allNodes = graphStore.findAll();
        List<Map<String, Object>> edges = new ArrayList<>();

        for (CodeNode node : allNodes) {
            for (CodeEdge edge : node.getEdges()) {
                if (kind != null && !kind.isBlank()
                        && !edge.getKind().getValue().equals(kind)) {
                    continue;
                }
                edges.add(edgeToMap(edge));
            }
        }

        int start = Math.min(offset, edges.size());
        int end = Math.min(start + limit, edges.size());
        List<Map<String, Object>> page = edges.subList(start, end);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("edges", page);
        result.put("count", page.size());
        result.put("total", edges.size());
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
        }
        if (edge.getProperties() != null && !edge.getProperties().isEmpty()) {
            m.put("properties", edge.getProperties());
        }
        return m;
    }
}
