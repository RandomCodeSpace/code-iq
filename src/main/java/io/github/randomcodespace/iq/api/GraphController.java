package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.query.QueryService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import io.github.randomcodespace.iq.intelligence.query.CapabilityMatrix;
import io.github.randomcodespace.iq.model.NodeKind;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * REST API controller matching the Python Code IQ API paths.
 */
@RestController
@RequestMapping("/api")
@Profile("serving")
public class GraphController {

    private final QueryService queryService;
    private final CodeIqConfig config;

    public GraphController(@org.springframework.beans.factory.annotation.Autowired(required = false) QueryService queryService,
                           CodeIqConfig config) {
        this.queryService = queryService;
        this.config = config;
    }

    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        requireQueryService();
        return queryService.getStats();
    }

    @GetMapping("/stats/detailed")
    public Map<String, Object> getDetailedStats(
            @RequestParam(defaultValue = "all") String category) {
        requireQueryService();
        return queryService.getDetailedStats(category);
    }

    @GetMapping("/kinds")
    public Map<String, Object> listKinds() {
        requireQueryService();
        return queryService.listKinds();
    }

    @GetMapping("/kinds/{kind}")
    public Map<String, Object> nodesByKind(
            @PathVariable String kind,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        requireQueryService();
        validateNodeKind(kind);
        return queryService.nodesByKind(kind, Math.min(limit, 1000), Math.max(0, offset));
    }

    @GetMapping("/nodes")
    public Map<String, Object> listNodes(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        requireQueryService();
        if (kind != null) {
            validateNodeKind(kind);
        }
        return queryService.listNodes(kind, Math.min(limit, 1000), Math.max(0, offset));
    }

    @GetMapping("/nodes/{nodeId}/detail")
    public Map<String, Object> nodeDetail(@PathVariable String nodeId) {
        requireQueryService();
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
        requireQueryService();
        return queryService.listEdges(kind, Math.min(limit, 1000), Math.max(0, offset));
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

    @GetMapping("/query/dead-code")
    public ResponseEntity<?> findDeadCode(
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "100") int limit) {
        requireQueryService();
        if (kind != null && !kind.isBlank()) {
            validateNodeKind(kind);
        }
        return ResponseEntity.ok(queryService.findDeadCode(kind, Math.min(limit, 1000)));
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
        requireQueryService();
        return queryService.searchGraph(q, Math.min(limit, 1000));
    }

    @GetMapping("/file-tree")
    public Map<String, Object> getFileTree(
            @RequestParam(required = false) Integer depth,
            @RequestParam(required = false) Integer maxFiles,
            @RequestParam(defaultValue = "true") boolean excludeTests) {
        requireQueryService();
        // depth=null means unlimited (full tree for treemap). Otherwise cap at maxDepth.
        Integer cappedDepth = (depth != null) ? Math.min(depth, config.getMaxDepth()) : null;
        // Default unlimited for treemap
        int limit = (maxFiles != null) ? maxFiles : Integer.MAX_VALUE;
        return queryService.getFileTree(cappedDepth, limit, excludeTests);
    }

    @GetMapping("/capabilities")
    public Map<String, Object> getCapabilities(
            @RequestParam(required = false) String language) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        if (language != null && !language.isBlank()) {
            result.put("language", language.strip().toLowerCase());
            result.put("capabilities", CapabilityMatrix.forLanguage(language).entrySet().stream()
                    .collect(java.util.stream.Collectors.toMap(
                            e -> e.getKey().name().toLowerCase(),
                            e -> e.getValue().name(),
                            (a, b) -> a,
                            java.util.TreeMap::new)));
        } else {
            result.put("matrix", CapabilityMatrix.asSerializableMap());
        }
        return result;
    }

    private void validateNodeKind(String kind) {
        try {
            NodeKind.fromValue(kind);
        } catch (IllegalArgumentException e) {
            String valid = Arrays.stream(NodeKind.values())
                    .map(NodeKind::getValue)
                    .collect(Collectors.joining(", "));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid node kind: '" + kind + "'. Valid values: " + valid);
        }
    }

    private void requireQueryService() {
        if (queryService == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Neo4j graph not available. This endpoint requires 'enrich' to be run first.");
        }
    }

    @GetMapping("/file")
    public ResponseEntity<String> readFile(
            @RequestParam String path,
            @RequestParam(required = false) Integer startLine,
            @RequestParam(required = false) Integer endLine) {
        Path codebaseReal;
        try {
            codebaseReal = Path.of(config.getRootPath()).toRealPath();
        } catch (IOException e) {
            return ResponseEntity.status(500)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Failed to resolve codebase root: " + e.getMessage());
        }
        Path candidate = codebaseReal.resolve(path).normalize();
        if (!candidate.startsWith(codebaseReal)) {
            return ResponseEntity.status(403)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Path traversal blocked");
        }
        Path resolvedReal;
        try {
            resolvedReal = candidate.toRealPath();
        } catch (NoSuchFileException e) {
            return ResponseEntity.notFound().build();
        } catch (IOException e) {
            return ResponseEntity.status(500)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Failed to resolve file: " + e.getMessage());
        }
        if (!resolvedReal.startsWith(codebaseReal)) {
            return ResponseEntity.status(403)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("Path traversal blocked");
        }
        if (!Files.isRegularFile(resolvedReal)) {
            return ResponseEntity.notFound().build();
        }
        try {
            String content = Files.readString(resolvedReal, StandardCharsets.UTF_8);
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

    // POST /api/analyze removed — API/MCP server is read-only.
    // Analysis is done locally via CLI: codeiq analyze / codeiq index
    // Data is loaded into Neo4j on serve startup (auto-enrich).
}
