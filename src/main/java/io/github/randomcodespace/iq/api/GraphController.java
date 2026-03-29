package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.analyzer.AnalysisResult;
import io.github.randomcodespace.iq.analyzer.Analyzer;
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.query.QueryService;
import io.github.randomcodespace.iq.query.StatsService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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

/**
 * REST API controller matching the Python OSSCodeIQ API paths.
 */
@RestController
@RequestMapping("/api")
public class GraphController {

    private final QueryService queryService;
    private final Analyzer analyzer;
    private final CodeIqConfig config;
    private final StatsService statsService;

    public GraphController(QueryService queryService, Analyzer analyzer,
                           CodeIqConfig config, StatsService statsService) {
        this.queryService = queryService;
        this.analyzer = analyzer;
        this.config = config;
        this.statsService = statsService;
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        return queryService.getStats();
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
        return queryService.listKinds();
    }

    @GetMapping("/kinds/{kind}")
    public Map<String, Object> nodesByKind(
            @PathVariable String kind,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return queryService.nodesByKind(kind, limit, offset);
    }

    @GetMapping("/nodes")
    public Map<String, Object> listNodes(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return queryService.listNodes(kind, limit, offset);
    }

    @GetMapping("/nodes/{nodeId}/detail")
    public Map<String, Object> nodeDetail(@PathVariable String nodeId) {
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
        return queryService.getNeighbors(nodeId, direction);
    }

    @GetMapping("/edges")
    public Map<String, Object> listEdges(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return queryService.listEdges(kind, limit, offset);
    }

    @GetMapping("/ego/{center}")
    public Map<String, Object> egoGraph(
            @PathVariable String center,
            @RequestParam(defaultValue = "2") int radius) {
        int cappedRadius = Math.min(radius, config.getMaxRadius());
        return queryService.egoGraph(center, cappedRadius);
    }

    @GetMapping("/query/cycles")
    public Map<String, Object> findCycles(@RequestParam(defaultValue = "100") int limit) {
        return queryService.findCycles(limit);
    }

    @GetMapping("/query/shortest-path")
    public Map<String, Object> shortestPath(
            @RequestParam String source,
            @RequestParam String target) {
        Map<String, Object> result = queryService.shortestPath(source, target);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No path found between " + source + " and " + target);
        }
        return result;
    }

    @GetMapping("/query/consumers/{targetId}")
    public Map<String, Object> consumersOf(@PathVariable String targetId) {
        return queryService.consumersOf(targetId);
    }

    @GetMapping("/query/producers/{targetId}")
    public Map<String, Object> producersOf(@PathVariable String targetId) {
        return queryService.producersOf(targetId);
    }

    @GetMapping("/query/callers/{targetId}")
    public Map<String, Object> callersOf(@PathVariable String targetId) {
        return queryService.callersOf(targetId);
    }

    @GetMapping("/query/dependencies/{moduleId}")
    public Map<String, Object> dependenciesOf(@PathVariable String moduleId) {
        return queryService.dependenciesOf(moduleId);
    }

    @GetMapping("/query/dependents/{moduleId}")
    public Map<String, Object> dependentsOf(@PathVariable String moduleId) {
        return queryService.dependentsOf(moduleId);
    }

    @GetMapping("/triage/component")
    public Map<String, Object> findComponent(@RequestParam String file) {
        return queryService.findComponentByFile(file);
    }

    @GetMapping("/triage/impact/{nodeId}")
    public Map<String, Object> traceImpact(
            @PathVariable String nodeId,
            @RequestParam(defaultValue = "3") int depth) {
        int cappedDepth = Math.min(depth, config.getMaxDepth());
        return queryService.traceImpact(nodeId, cappedDepth);
    }

    @GetMapping("/search")
    public List<Map<String, Object>> searchGraph(
            @RequestParam String q,
            @RequestParam(defaultValue = "50") int limit) {
        return queryService.searchGraph(q, limit);
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
    public Map<String, Object> triggerAnalysis(
            @RequestParam(defaultValue = "false") boolean incremental) {
        AnalysisResult result = analyzer.run(Path.of(config.getRootPath()), null);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "complete");
        response.put("total_files", result.totalFiles());
        response.put("files_analyzed", result.filesAnalyzed());
        response.put("node_count", result.nodeCount());
        response.put("edge_count", result.edgeCount());
        response.put("elapsed_ms", result.elapsed().toMillis());
        return response;
    }
}
