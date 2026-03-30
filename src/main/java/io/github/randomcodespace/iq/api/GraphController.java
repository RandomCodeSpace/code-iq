package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.analyzer.AnalysisResult;
import io.github.randomcodespace.iq.analyzer.Analyzer;
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.query.QueryService;
import io.github.randomcodespace.iq.query.StatsService;
import io.github.randomcodespace.iq.query.TopologyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * REST API controller matching the Python OSSCodeIQ API paths.
 */
@RestController
@RequestMapping("/api")
@Profile("serving")
public class GraphController {

    private final QueryService queryService;
    private final Analyzer analyzer;
    private final CodeIqConfig config;
    private final StatsService statsService;
    private final TopologyService topologyService;
    private final AtomicBoolean analysisRunning = new AtomicBoolean(false);

    public GraphController(@org.springframework.beans.factory.annotation.Autowired(required = false) QueryService queryService,
                           Analyzer analyzer,
                           CodeIqConfig config, StatsService statsService,
                           TopologyService topologyService) {
        this.queryService = queryService;
        this.analyzer = analyzer;
        this.config = config;
        this.statsService = statsService;
        this.topologyService = topologyService;
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        // Use H2 cache for stats — more memory-efficient than loading entire Neo4j graph
        var data = loadFromH2();
        if (data != null) {
            return statsService.computeStats(data.nodes(), data.edges());
        }
        if (queryService != null) {
            return queryService.getStats();
        }
        return Map.of("error", "No analysis data available. Run analyze first.");
    }

    @GetMapping("/stats/detailed")
    public Map<String, Object> getDetailedStats(
            @RequestParam(defaultValue = "all") String category) {
        Path root = Path.of(config.getRootPath()).toAbsolutePath().normalize();
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        // H2 stores data in analysis-cache.mv.db — check for that file on disk
        Path h2File = root.resolve(config.getCacheDir()).resolve("analysis-cache.mv.db");

        if (!Files.exists(h2File)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No analysis cache found. Run analyze first.");
        }

        List<CodeNode> nodes;
        List<CodeEdge> edges;
        try (AnalysisCache cache = new AnalysisCache(cachePath)) {
            nodes = cache.loadAllNodes();
            edges = cache.loadAllEdges();
        }

        if ("all".equalsIgnoreCase(category)) {
            return statsService.computeStats(nodes, edges);
        }
        Map<String, Object> catStats = statsService.computeCategory(nodes, edges, category);
        if (catStats == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Unknown category: " + category);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(category.toLowerCase(), catStats);
        return result;
    }

    @GetMapping("/kinds")
    public Map<String, Object> listKinds() {
        var data = loadFromH2();
        if (data != null) {
            Map<String, Long> kindCounts = data.nodes().stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            n -> n.getKind().getValue(),
                            java.util.stream.Collectors.counting()));
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
            result.put("total", data.nodes().size());
            return result;
        }
        if (queryService != null) return queryService.listKinds();
        return Map.of("error", "No data available");
    }

    @GetMapping("/kinds/{kind}")
    public Map<String, Object> nodesByKind(
            @PathVariable String kind,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        var data = loadFromH2();
        if (data != null) {
            List<CodeNode> filtered = data.nodes().stream()
                    .filter(n -> n.getKind().getValue().equals(kind))
                    .toList();
            int start = Math.min(offset, filtered.size());
            int end = Math.min(start + limit, filtered.size());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("kind", kind);
            result.put("total", filtered.size());
            result.put("offset", offset);
            result.put("limit", limit);
            result.put("nodes", filtered.subList(start, end).stream().map(this::nodeToMap).toList());
            return result;
        }
        if (queryService != null) return queryService.nodesByKind(kind, limit, offset);
        return Map.of("error", "No data available");
    }

    @GetMapping("/nodes")
    public Map<String, Object> listNodes(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        var data = loadFromH2();
        if (data != null) {
            List<CodeNode> filtered = data.nodes();
            if (kind != null && !kind.isBlank()) {
                filtered = filtered.stream()
                        .filter(n -> n.getKind().getValue().equals(kind))
                        .toList();
            }
            int start = Math.min(offset, filtered.size());
            int end = Math.min(start + limit, filtered.size());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("nodes", filtered.subList(start, end).stream().map(this::nodeToMap).toList());
            result.put("count", end - start);
            result.put("offset", offset);
            result.put("limit", limit);
            return result;
        }
        if (queryService != null) return queryService.listNodes(kind, limit, offset);
        return Map.of("error", "No data available");
    }

    @GetMapping("/nodes/find")
    public List<Map<String, Object>> findNode(@RequestParam String q) {
        Path root = Path.of(config.getRootPath()).toAbsolutePath().normalize();
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        Path h2File = root.resolve(config.getCacheDir()).resolve("analysis-cache.mv.db");

        if (!Files.exists(h2File)) {
            return List.of();
        }

        try (AnalysisCache cache = new AnalysisCache(cachePath)) {
            List<CodeNode> nodes = cache.loadAllNodes();
            return topologyService.findNode(q, nodes);
        }
    }

    @GetMapping("/nodes/{nodeId}/detail")
    public Map<String, Object> nodeDetail(@PathVariable String nodeId) {
        // Try H2 first
        var data = loadFromH2();
        if (data != null) {
            return data.nodes().stream()
                    .filter(n -> nodeId.equals(n.getId()))
                    .findFirst()
                    .map(this::nodeToMap)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Node not found: " + nodeId));
        }
        if (queryService == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Node not found: " + nodeId);
        Map<String, Object> result = queryService.nodeDetailWithEdges(nodeId);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Node not found: " + nodeId);
        }
        return result;
    }

    @GetMapping("/nodes/{nodeId}/neighbors")
    public Map<String, Object> neighbors(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "both") String direction) {
        requireQueryService();
        return queryService.getNeighbors(nodeId, direction);
    }

    @GetMapping("/edges")
    public Map<String, Object> listEdges(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        var data = loadFromH2();
        if (data != null) {
            List<CodeEdge> filtered = data.edges();
            if (kind != null && !kind.isBlank()) {
                filtered = filtered.stream()
                        .filter(e -> e.getKind().getValue().equals(kind))
                        .toList();
            }
            int start = Math.min(offset, filtered.size());
            int end = Math.min(start + limit, filtered.size());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("edges", filtered.subList(start, end).stream().map(this::edgeToMap).toList());
            result.put("count", end - start);
            result.put("total", filtered.size());
            return result;
        }
        requireQueryService();
        return queryService.listEdges(kind, limit, offset);
    }

    @GetMapping("/ego/{center}")
    public Map<String, Object> egoGraph(
            @PathVariable String center,
            @RequestParam(defaultValue = "2") int radius) {
        int cappedRadius = Math.min(radius, config.getMaxRadius());
        requireQueryService();
        return queryService.egoGraph(center, cappedRadius);
    }

    @GetMapping("/query/cycles")
    public Map<String, Object> findCycles(@RequestParam(defaultValue = "100") int limit) {
        requireQueryService();
        return queryService.findCycles(limit);
    }

    @GetMapping("/query/shortest-path")
    public Map<String, Object> shortestPath(
            @RequestParam String source,
            @RequestParam String target) {
        requireQueryService();
        Map<String, Object> result = queryService.shortestPath(source, target);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No path found between " + source + " and " + target);
        }
        return result;
    }

    @GetMapping("/query/consumers/{targetId}")
    public Map<String, Object> consumersOf(@PathVariable String targetId) {
        requireQueryService();
        return queryService.consumersOf(targetId);
    }

    @GetMapping("/query/producers/{targetId}")
    public Map<String, Object> producersOf(@PathVariable String targetId) {
        requireQueryService();
        return queryService.producersOf(targetId);
    }

    @GetMapping("/query/callers/{targetId}")
    public Map<String, Object> callersOf(@PathVariable String targetId) {
        requireQueryService();
        return queryService.callersOf(targetId);
    }

    @GetMapping("/query/dependencies/{moduleId}")
    public Map<String, Object> dependenciesOf(@PathVariable String moduleId) {
        requireQueryService();
        return queryService.dependenciesOf(moduleId);
    }

    @GetMapping("/query/dependents/{moduleId}")
    public Map<String, Object> dependentsOf(@PathVariable String moduleId) {
        requireQueryService();
        return queryService.dependentsOf(moduleId);
    }

    @GetMapping("/triage/component")
    public Map<String, Object> findComponent(@RequestParam String file) {
        requireQueryService();
        return queryService.findComponentByFile(file);
    }

    @GetMapping("/triage/impact/{nodeId}")
    public Map<String, Object> traceImpact(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "3") int depth) {
        int cappedDepth = Math.min(depth, config.getMaxDepth());
        requireQueryService();
        return queryService.traceImpact(nodeId, cappedDepth);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> searchGraph(
            @RequestParam String q,
            @RequestParam(defaultValue = "50") int limit) {
        // Search from H2
        var data = loadFromH2();
        if (data != null) {
            return topologyService.findNode(q, data.nodes());
        }
        if (queryService != null) return queryService.searchGraph(q, limit);
        return List.of();
    }

    private void requireQueryService() {
        if (queryService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Neo4j graph not available. This endpoint requires 'enrich' to be run first.");
        }
    }

    private Map<String, Object> edgeToMap(CodeEdge edge) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", edge.getId());
        m.put("kind", edge.getKind().getValue());
        m.put("source", edge.getSourceId());
        if (edge.getTarget() != null) {
            m.put("target", edge.getTarget().getId());
        }
        return m;
    }

    @GetMapping("/file")
    public ResponseEntity<String> readFile(
            @RequestParam String path,
            @RequestParam(required = false) Integer startLine,
            @RequestParam(required = false) Integer endLine) {
        Path codebasePath = Path.of(config.getRootPath()).toAbsolutePath().normalize();
        Path resolved = codebasePath.resolve(path).normalize();
        if (!resolved.startsWith(codebasePath)) {
            return ResponseEntity.status(403)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Path traversal blocked");
        }
        if (!Files.isRegularFile(resolved)) {
            return ResponseEntity.notFound().build();
        }
        try {
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
            if (startLine != null || endLine != null) {
                String[] lines = content.split("\n", -1);
                int start = (startLine != null ? startLine : 1);
                int end = (endLine != null ? endLine : lines.length);
                start = Math.max(1, Math.min(start, lines.length));
                end = Math.max(start, Math.min(end, lines.length));
                StringBuilder sb = new StringBuilder();
                for (int i = start - 1; i < end; i++) {
                    if (i > start - 1) sb.append('\n');
                    sb.append(lines[i]);
                }
                content = sb.toString();
            }
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(content);
        } catch (IOException e) {
            return ResponseEntity.status(500)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Failed to read file: " + e.getMessage());
        }
    }

    @PostMapping("/analyze")
    public ResponseEntity<?> triggerAnalysis(
            @RequestParam(defaultValue = "false") boolean incremental) {
        if (!analysisRunning.compareAndSet(false, true)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "Analysis already in progress"));
        }
        try {
            AnalysisResult result = analyzer.run(Path.of(config.getRootPath()), null);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "complete");
            response.put("total_files", result.totalFiles());
            response.put("files_analyzed", result.filesAnalyzed());
            response.put("node_count", result.nodeCount());
            response.put("edge_count", result.edgeCount());
            response.put("elapsed_ms", result.elapsed().toMillis());
            return ResponseEntity.ok(response);
        } finally {
            analysisRunning.set(false);
        }
    }

    // --- H2 cache helpers ---

    private record H2Data(List<CodeNode> nodes, List<CodeEdge> edges) {}

    /**
     * Load nodes and edges from the H2 analysis cache, or null if not available.
     */
    private H2Data loadFromH2() {
        Path root = Path.of(config.getRootPath()).toAbsolutePath().normalize();
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        Path h2File = root.resolve(config.getCacheDir()).resolve("analysis-cache.mv.db");

        if (!Files.exists(h2File)) {
            return null;
        }

        try (AnalysisCache cache = new AnalysisCache(cachePath)) {
            List<CodeNode> nodes = cache.loadAllNodes();
            List<CodeEdge> edges = cache.loadAllEdges();
            return new H2Data(nodes, edges);
        }
    }

    private Map<String, Object> nodeToMap(CodeNode node) {
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
}
