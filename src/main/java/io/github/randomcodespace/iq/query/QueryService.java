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
        long nodeCount = graphStore.count();
        long edgeCount = graphStore.countEdges();
        long fileCount = graphStore.countDistinctFiles();

        Map<String, Long> nodesByKind = new LinkedHashMap<>();
        for (Map<String, Object> row : graphStore.countNodesByKind()) {
            nodesByKind.put((String) row.get("kind"), ((Number) row.get("cnt")).longValue());
        }

        Map<String, Long> nodesByLayer = new LinkedHashMap<>();
        for (Map<String, Object> row : graphStore.countNodesByLayer()) {
            nodesByLayer.put((String) row.get("layer"), ((Number) row.get("cnt")).longValue());
        }

        // Language breakdown from file extensions
        Map<String, Long> languages = new LinkedHashMap<>();
        for (Map<String, Object> row : graphStore.countByFileExtension()) {
            String ext = (String) row.get("ext");
            long cnt = ((Number) row.get("cnt")).longValue();
            String lang = extToLanguage(ext);
            languages.merge(lang, cnt, Long::sum);
        }

        // Return in ComputedStatsResponse format for frontend compatibility
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", nodeCount);
        graph.put("edges", edgeCount);
        graph.put("files", fileCount);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("graph", graph);
        result.put("languages", languages);
        result.put("frameworks", Map.of());
        result.put("infra", Map.of("databases", Map.of(), "messaging", Map.of(), "cloud", Map.of()));
        result.put("connections", Map.of("rest", Map.of("total", 0, "by_method", Map.of()),
                "grpc", 0, "websocket", 0, "producers", 0, "consumers", 0));
        result.put("auth", Map.of());
        result.put("architecture", Map.of());
        // Also include raw counts for backward compat
        result.put("node_count", nodeCount);
        result.put("edge_count", edgeCount);
        result.put("nodes_by_kind", nodesByKind);
        result.put("nodes_by_layer", nodesByLayer);
        return result;
    }

    private static String extToLanguage(String ext) {
        if (ext == null) return "unknown";
        return switch (ext.toLowerCase()) {
            case "java" -> "java";
            case "kt", "kts" -> "kotlin";
            case "py" -> "python";
            case "js", "mjs", "cjs" -> "javascript";
            case "ts", "tsx" -> "typescript";
            case "go" -> "go";
            case "rs" -> "rust";
            case "cs" -> "csharp";
            case "scala" -> "scala";
            case "cpp", "cc", "cxx", "h", "hpp" -> "cpp";
            case "c" -> "c";
            case "rb" -> "ruby";
            case "proto" -> "protobuf";
            case "yml", "yaml" -> "yaml";
            case "json" -> "json";
            case "xml" -> "xml";
            case "tf" -> "terraform";
            case "sql" -> "sql";
            case "md" -> "markdown";
            case "html", "htm" -> "html";
            case "css", "scss", "sass" -> "css";
            case "vue" -> "vue";
            case "svelte" -> "svelte";
            case "jsx" -> "jsx";
            case "sh", "bash" -> "shell";
            default -> ext;
        };
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

    // --- Topology ---

    @Cacheable("topology")
    public Map<String, Object> getTopology() {
        return graphStore.getTopology();
    }

    // --- Dead code detection ---

    @Cacheable(value = "dead-code", key = "#kind + ':' + #limit")
    public Map<String, Object> findDeadCode(String kind, int limit) {
        List<String> kinds;
        if (kind != null && !kind.isBlank()) {
            kinds = List.of(kind);
        } else {
            kinds = List.of(
                    NodeKind.CLASS.getValue(),
                    NodeKind.METHOD.getValue(),
                    NodeKind.INTERFACE.getValue());
        }

        List<CodeNode> deadNodes = graphStore.findNodesWithoutIncoming(kinds, 0, limit);

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
        }
        if (edge.getProperties() != null && !edge.getProperties().isEmpty()) {
            m.put("properties", edge.getProperties());
        }
        return m;
    }
}
