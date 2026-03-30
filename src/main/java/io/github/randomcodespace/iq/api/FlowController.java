package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.flow.FlowModels.FlowDiagram;
import io.github.randomcodespace.iq.model.CodeNode;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * REST API controller for architecture flow diagrams.
 * Works from either Neo4j (GraphStore) or H2 cache.
 */
@RestController
@RequestMapping("/api/flow")
@Profile("serving")
public class FlowController {

    private final FlowEngine flowEngine;
    private final CodeIqConfig config;

    public FlowController(Optional<FlowEngine> flowEngine, CodeIqConfig config) {
        this.flowEngine = flowEngine.orElse(null);
        this.config = config;
    }

    @GetMapping
    public Map<String, Object> getAllFlows() {
        FlowEngine engine = resolveEngine();
        var allViews = engine.generateAll();
        var result = new LinkedHashMap<String, Object>();
        for (var entry : allViews.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toMap());
        }
        return result;
    }

    @GetMapping("/{view}")
    public ResponseEntity<?> getFlow(
            @PathVariable String view,
            @RequestParam(defaultValue = "json") String format) {
        try {
            FlowEngine engine = resolveEngine();
            FlowDiagram diagram = engine.generate(view);

            return switch (format.toLowerCase()) {
                case "json" -> ResponseEntity.ok(diagram.toMap());
                case "mermaid" -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(engine.render(diagram, "mermaid"));
                case "html" -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(engine.renderInteractive("Project"));
                default -> throw new IllegalArgumentException(
                        "Unknown format. Available: json, mermaid, html");
            };
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @GetMapping("/{view}/{nodeId}/children")
    public Map<String, Object> getChildren(
            @PathVariable String view,
            @PathVariable String nodeId) {
        FlowEngine engine = resolveEngine();
        Map<String, Object> children = engine.getChildren(view, nodeId);
        if (children == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No drill-down available for node " + nodeId + " in view " + view);
        }
        return children;
    }

    @GetMapping("/{view}/{nodeId}/parent")
    public Map<String, Object> getParent(
            @PathVariable String view,
            @PathVariable String nodeId) {
        FlowEngine engine = resolveEngine();
        Map<String, Object> parent = engine.getParentContext(nodeId);
        if (parent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No parent context found for node " + nodeId);
        }
        return parent;
    }

    private FlowEngine resolveEngine() {
        if (flowEngine != null) {
            return flowEngine;
        }

        // Fall back to H2 cache
        Path root = Path.of(config.getRootPath()).toAbsolutePath().normalize();
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        Path h2File = root.resolve(config.getCacheDir()).resolve("analysis-cache.mv.db");

        if (!Files.exists(h2File)) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No analysis data available. Run 'code-iq analyze' first.");
        }

        try (AnalysisCache cache = new AnalysisCache(cachePath)) {
            List<CodeNode> nodes = cache.loadAllNodes();
            if (nodes.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Analysis cache is empty. Run 'code-iq analyze' first.");
            }
            return FlowEngine.fromCache(nodes);
        }
    }
}
