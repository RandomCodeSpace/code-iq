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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * High-level query service wrapping GraphStore with caching.
 * All methods return simple Map/List structures for JSON serialization.
 */
@Service
@ConditionalOnBean(GraphStore.class)
public class QueryService {
    private static final String PROP_CHILDREN = "children";
    private static final String PROP_CNT = "cnt";
    private static final String PROP_COUNT = "count";
    private static final String PROP_DIRECTORY = "directory";
    private static final String PROP_FILE = "file";
    private static final String PROP_ID = "id";
    private static final String PROP_KIND = "kind";
    private static final String PROP_LAYER = "layer";
    private static final String PROP_MODULE = "module";
    private static final String PROP_NODECOUNT = "nodeCount";
    private static final String PROP_NODES = "nodes";
    private static final String PROP_PATH = "path";
    private static final String PROP_SOURCE = "source";
    private static final String PROP_TARGET = "target";
    private static final String PROP_TOTAL = "total";


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
            nodesByKind.put((String) row.get(PROP_KIND), ((Number) row.get(PROP_CNT)).longValue());
        }
        Map<String, Long> nodesByLayer = new LinkedHashMap<>();
        for (Map<String, Object> row : graphStore.countNodesByLayer()) {
            nodesByLayer.put((String) row.get(PROP_LAYER), ((Number) row.get(PROP_CNT)).longValue());
        }

        // Read from already-computed graph sub-map instead of re-querying
        @SuppressWarnings("unchecked")
        Map<String, Object> graphStats = (Map<String, Object>) result.get("graph");
        if (graphStats != null) {
            result.put("node_count", graphStats.get(PROP_NODES));
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
                        ((Number) b.get(PROP_CNT)).longValue(),
                        ((Number) a.get(PROP_CNT)).longValue()))
                .forEach(row -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put(PROP_KIND, row.get(PROP_KIND));
                    m.put(PROP_COUNT, ((Number) row.get(PROP_CNT)).longValue());
                    kinds.add(m);
                });
        long totalNodes = rawCounts.stream()
                .mapToLong(r -> ((Number) r.get(PROP_CNT)).longValue())
                .sum();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kinds", kinds);
        result.put(PROP_TOTAL, totalNodes);
        return result;
    }

    @Cacheable(value = "kind-nodes", key = "#kind + ':' + #offset + ':' + #limit")
    public Map<String, Object> nodesByKind(String kind, int limit, int offset) {
        List<CodeNode> nodes = graphStore.findByKindPaginated(kind, offset, limit);
        long total = graphStore.countByKind(kind);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PROP_KIND, kind);
        result.put(PROP_TOTAL, total);
        result.put("offset", offset);
        result.put("limit", limit);
        result.put(PROP_NODES, nodes.stream().map(this::nodeToMap).toList());
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
        result.put(PROP_NODES, nodes.stream().map(this::nodeToMap).toList());
        result.put(PROP_COUNT, nodes.size());
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
            m.put(PROP_ID, row.get(PROP_ID));
            m.put(PROP_KIND, row.get(PROP_KIND));
            m.put(PROP_SOURCE, row.get("sourceId"));
            m.put(PROP_TARGET, row.get("targetId"));
            return m;
        }).toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("edges", edges);
        result.put(PROP_COUNT, edges.size());
        result.put(PROP_TOTAL, total);
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
        result.put(PROP_COUNT, neighbors.size());
        return result;
    }

    // --- Graph traversal queries ---

    public Map<String, Object> shortestPath(String source, String target) {
        List<String> path = graphStore.findShortestPath(source, target);
        if (path == null || path.isEmpty()) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PROP_SOURCE, source);
        result.put(PROP_TARGET, target);
        result.put(PROP_PATH, path);
        result.put("length", path.size() - 1);
        return result;
    }

    public Map<String, Object> findCycles(int limit) {
        int cappedLimit = Math.min(limit, 1000);
        List<List<String>> cycles = graphStore.findCycles(cappedLimit);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cycles", cycles);
        result.put(PROP_COUNT, cycles.size());
        return result;
    }

    @Cacheable(value = "impact-trace", key = "#nodeId + ':' + #depth")
    public Map<String, Object> traceImpact(String nodeId, int depth) {
        int cappedDepth = Math.min(depth, config.getMaxDepth());
        List<CodeNode> impacted = graphStore.traceImpact(nodeId, cappedDepth);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PROP_SOURCE, nodeId);
        result.put("depth", cappedDepth);
        result.put("impacted", impacted.stream().map(this::nodeToMap).toList());
        result.put(PROP_COUNT, impacted.size());
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
        result.put(PROP_NODES, nodes.stream().map(this::nodeToMap).toList());
        result.put(PROP_COUNT, nodes.size());
        return result;
    }

    // --- Relationship queries ---

    @Cacheable(value = "consumers", key = "#targetId")
    public Map<String, Object> consumersOf(String targetId) {
        List<CodeNode> consumers = graphStore.findConsumers(targetId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PROP_TARGET, targetId);
        result.put("consumers", consumers.stream().map(this::nodeToMap).toList());
        result.put(PROP_COUNT, consumers.size());
        return result;
    }

    public Map<String, Object> producersOf(String targetId) {
        List<CodeNode> producers = graphStore.findProducers(targetId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PROP_TARGET, targetId);
        result.put("producers", producers.stream().map(this::nodeToMap).toList());
        result.put(PROP_COUNT, producers.size());
        return result;
    }

    @Cacheable(value = "callers", key = "#targetId")
    public Map<String, Object> callersOf(String targetId) {
        List<CodeNode> callers = graphStore.findCallers(targetId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PROP_TARGET, targetId);
        result.put("callers", callers.stream().map(this::nodeToMap).toList());
        result.put(PROP_COUNT, callers.size());
        return result;
    }

    public Map<String, Object> dependenciesOf(String moduleId) {
        List<CodeNode> deps = graphStore.findDependencies(moduleId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PROP_MODULE, moduleId);
        result.put("dependencies", deps.stream().map(this::nodeToMap).toList());
        result.put(PROP_COUNT, deps.size());
        return result;
    }

    public Map<String, Object> dependentsOf(String moduleId) {
        List<CodeNode> deps = graphStore.findDependents(moduleId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PROP_MODULE, moduleId);
        result.put("dependents", deps.stream().map(this::nodeToMap).toList());
        result.put(PROP_COUNT, deps.size());
        return result;
    }

    // --- Triage queries ---

    @Cacheable(value = "component-by-file", key = "#filePath")
    public Map<String, Object> findComponentByFile(String filePath) {
        List<CodeNode> nodes = graphStore.findByFilePath(filePath);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(PROP_FILE, filePath);
        result.put(PROP_NODES, nodes.stream().map(this::nodeToMap).toList());
        result.put(PROP_COUNT, nodes.size());

        if (!nodes.isEmpty()) {
            CodeNode first = nodes.getFirst();
            result.put(PROP_MODULE, first.getModule());
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
     * Build a hierarchical file-tree from all distinct filePaths in the graph.
     * Each directory node carries an aggregate nodeCount (sum of all descendants).
     * Each file node carries the count of CodeNodes at that exact path.
     * Children are sorted: directories first (alphabetical), then files (alphabetical).
     *
     * @param maxDepth limit tree depth; null means unlimited
     */
    public Map<String, Object> getFileTree(Integer maxDepth) {
        return getFileTree(maxDepth, config.getMaxFiles(), true);
    }

    private static final java.util.Set<String> TEST_DIR_NAMES = java.util.Set.of(
            "test", "tests", "__tests__", "__test__", "spec", "specs",
            "test-utils", "testing", "e2e", "integration-tests",
            "unit-tests", "testdata", "test-data", "fixtures");

    private static final java.util.regex.Pattern TEST_FILE_PATTERN =
            java.util.regex.Pattern.compile("(?i)(test|spec|_test|_spec)\\.[^.]+$");

    private static boolean isTestPath(String filePath) {
        String[] parts = filePath.split("/");
        for (String part : parts) {
            if (TEST_DIR_NAMES.contains(part.toLowerCase())) return true;
        }
        // Check filename pattern (last segment)
        String fileName = parts[parts.length - 1];
        return TEST_FILE_PATTERN.matcher(fileName).find();
    }

    public Map<String, Object> getFileTree(Integer maxDepth, int maxFiles, boolean excludeTests) {
        return getFileTree(maxDepth, maxFiles, excludeTests, null);
    }

    /**
     * Path-rooted variant of file-tree. When {@code path} is non-null, the returned tree
     * is rooted at that filesystem path (its descendants only). Output {@code path}
     * fields remain absolute so the frontend can stitch the subtree back into the
     * full tree at the matching node without bookkeeping.
     */
    @Cacheable(
            value = "file-tree",
            key = "#maxDepth + '-' + #maxFiles + '-' + #excludeTests + '-' + (#path == null ? '_root' : #path)")
    public Map<String, Object> getFileTree(Integer maxDepth, int maxFiles, boolean excludeTests, String path) {
        GraphStore.FilePathResult filePathResult = graphStore.getFilePathsWithCounts(maxFiles, path);
        List<Map<String, Object>> rows = excludeTests
                ? filePathResult.rows().stream().filter(r -> !isTestPath((String) r.get("filePath"))).toList()
                : filePathResult.rows();

        // When path-rooted, strip the prefix before walking so the local TreeNode root
        // represents the focused directory rather than the absolute filesystem root.
        String prefix = (path != null && !path.isBlank()) ? path + "/" : "";
        TreeNode root = new TreeNode("", PROP_DIRECTORY);
        for (Map<String, Object> row : rows) {
            String filePath = (String) row.get("filePath");
            long count = ((Number) row.get(PROP_NODECOUNT)).longValue();
            String walkPath = prefix.isEmpty()
                    ? filePath
                    : (filePath.startsWith(prefix) ? filePath.substring(prefix.length()) : filePath);
            String[] parts = walkPath.split("/", -1);
            TreeNode current = root;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                if (part.isEmpty()) continue;
                boolean isFile = (i == parts.length - 1);
                String type = isFile ? PROP_FILE : PROP_DIRECTORY;
                TreeNode child = current.children.computeIfAbsent(part, k -> new TreeNode(k, type));
                // Upgrade FILE→DIRECTORY if this segment is used as an intermediate path.
                // This happens when e.g. "packages/api" (SERVICE node, file) exists AND
                // "packages/api/src/index.ts" (source file) also exists.
                if (!isFile && PROP_FILE.equals(child.type)) {
                    child.type = PROP_DIRECTORY;
                }
                if (isFile) {
                    child.nodeCount += count;
                }
                current = child;
            }
        }

        // Pass the focused path as the parentPath so emitted "path" fields are absolute
        // — frontend can match these directly against the node it's expanding.
        String outputParentPath = (path != null && !path.isBlank()) ? path : "";
        List<Map<String, Object>> tree = buildTreeOutput(root, maxDepth, 1, outputParentPath);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tree", tree);
        result.put("total_files", (long) rows.size());
        result.put("truncated", filePathResult.truncated());
        return result;
    }

    private List<Map<String, Object>> buildTreeOutput(TreeNode node, Integer maxDepth, int currentDepth, String parentPath) {
        List<Map<String, Object>> output = new ArrayList<>();

        List<TreeNode> dirs = node.children.values().stream()
                .filter(n -> "directory".equals(n.type))
                .sorted(Comparator.comparing(n -> n.name))
                .toList();
        List<TreeNode> files = node.children.values().stream()
                .filter(n -> "file".equals(n.type))
                .sorted(Comparator.comparing(n -> n.name))
                .toList();

        for (TreeNode child : dirs) {
            String childPath = parentPath.isEmpty() ? child.name : parentPath + "/" + child.name;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", child.name);
            m.put(PROP_PATH, childPath);
            m.put("type", PROP_DIRECTORY);
            m.put(PROP_NODECOUNT, aggregateCount(child));
            if (maxDepth == null || currentDepth < maxDepth) {
                m.put(PROP_CHILDREN, buildTreeOutput(child, maxDepth, currentDepth + 1, childPath));
            } else {
                m.put(PROP_CHILDREN, List.of());
            }
            output.add(m);
        }
        for (TreeNode child : files) {
            String childPath = parentPath.isEmpty() ? child.name : parentPath + "/" + child.name;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", child.name);
            m.put(PROP_PATH, childPath);
            m.put("type", PROP_FILE);
            m.put(PROP_NODECOUNT, child.nodeCount);
            m.put(PROP_CHILDREN, List.of());
            output.add(m);
        }
        return output;
    }

    private long aggregateCount(TreeNode node) {
        long total = node.nodeCount;
        for (TreeNode child : node.children.values()) {
            total += aggregateCount(child);
        }
        return total;
    }

    private static class TreeNode {
        final String name;
        String type; // mutable: FILE may be upgraded to DIRECTORY if later paths use it as an intermediate
        long nodeCount = 0;
        final Map<String, TreeNode> children = new TreeMap<>();

        TreeNode(String name, String type) {
            this.name = name;
            this.type = type;
        }
    }

    /**
     * Find API endpoints related to an identifier (file, class, entity).
     * Searches for matching nodes, then traverses the graph to find connected endpoints.
     * Uses a batch query instead of N+1 individual neighbor lookups.
     */
    @Cacheable(value = "related-endpoints", key = "#identifier")
    public Map<String, Object> findRelatedEndpoints(String identifier) {
        List<CodeNode> matches = graphStore.search(identifier, 50);

        Set<String> seenIds = new java.util.LinkedHashSet<>();
        List<Map<String, Object>> endpoints = new ArrayList<>();
        List<String> nonEndpointIds = new ArrayList<>();

        // Partition: collect direct endpoint matches, queue rest for batch lookup
        for (CodeNode match : matches) {
            if (match.getKind() == NodeKind.ENDPOINT || match.getKind() == NodeKind.WEBSOCKET_ENDPOINT) {
                if (seenIds.add(match.getId())) {
                    endpoints.add(nodeToMap(match));
                }
            } else {
                nonEndpointIds.add(match.getId());
            }
        }

        // Single batch query for all endpoint neighbors — replaces up to 50 individual findNeighbors() calls
        Map<String, List<CodeNode>> neighborEndpoints = graphStore.findEndpointNeighborsBatch(nonEndpointIds);
        for (Map.Entry<String, List<CodeNode>> entry : neighborEndpoints.entrySet()) {
            for (CodeNode neighbor : entry.getValue()) {
                if (seenIds.add(neighbor.getId())) {
                    Map<String, Object> epMap = nodeToMap(neighbor);
                    epMap.put("connected_via", entry.getKey());
                    endpoints.add(epMap);
                }
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("identifier", identifier);
        result.put("endpoints", endpoints);
        result.put(PROP_COUNT, endpoints.size());
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
            "calls", "imports", "depends_on", "extends", "implements",
            "injects", "queries", "maps_to", "consumes", "listens",
            "invokes_rmi", "overrides", "connects_to", "triggers", "renders",
            "protects");

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
            NodeKind.CONFIG_DEFINITION.getValue(),
            NodeKind.GUARD.getValue(),
            NodeKind.MIDDLEWARE.getValue(),
            NodeKind.TOPIC.getValue(),
            NodeKind.QUEUE.getValue(),
            NodeKind.EVENT.getValue(),
            NodeKind.MESSAGE_QUEUE.getValue());

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
                    m.put(PROP_ID, n.getId());
                    m.put(PROP_KIND, n.getKind().getValue());
                    m.put("label", n.getLabel());
                    m.put(PROP_FILE, n.getFilePath());
                    return m;
                })
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("dead_code", deadCode);
        result.put(PROP_COUNT, deadCode.size());
        return result;
    }

    // --- Serialization helpers ---

    Map<String, Object> nodeToMap(CodeNode node) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(PROP_ID, node.getId());
        m.put(PROP_KIND, node.getKind().getValue());
        m.put("label", node.getLabel());
        if (node.getFqn() != null) m.put("fqn", node.getFqn());
        if (node.getModule() != null) m.put(PROP_MODULE, node.getModule());
        if (node.getFilePath() != null) m.put("file_path", node.getFilePath());
        if (node.getLineStart() != null) m.put("line_start", node.getLineStart());
        if (node.getLineEnd() != null) m.put("line_end", node.getLineEnd());
        if (node.getLayer() != null) m.put(PROP_LAYER, node.getLayer());
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
        m.put(PROP_ID, edge.getId());
        m.put(PROP_KIND, edge.getKind().getValue());
        m.put(PROP_SOURCE, edge.getSourceId());
        if (edge.getTarget() != null) {
            m.put(PROP_TARGET, edge.getTarget().getId());
            m.put("target_label", edge.getTarget().getLabel());
            m.put("target_kind", edge.getTarget().getKind().getValue());
        }
        if (edge.getProperties() != null && !edge.getProperties().isEmpty()) {
            m.put("properties", edge.getProperties());
        }
        return m;
    }
}
