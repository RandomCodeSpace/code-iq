package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.flow.FlowModels.FlowDiagram;
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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API controller for architecture flow diagrams.
 * Supports drill-down and drill-up navigation.
 */
@RestController
@RequestMapping("/api/flow")
@Profile("serving")
public class FlowController {

    private final FlowEngine flowEngine;

    public FlowController(FlowEngine flowEngine) {
        this.flowEngine = flowEngine;
    }

    /**
     * Get all flow views as JSON diagrams.
     */
    @GetMapping
    public Map<String, Object> getAllFlows() {
        var allViews = flowEngine.generateAll();
        var result = new LinkedHashMap<String, Object>();
        for (var entry : allViews.entrySet()) {
            result.put(entry.getKey(), entry.getValue().toMap());
        }
        return result;
    }

    /**
     * Get a specific flow view, optionally in a different format.
     */
    @GetMapping("/{view}")
    public ResponseEntity<?> getFlow(
            @PathVariable String view,
            @RequestParam(defaultValue = "json") String format) {
        try {
            FlowDiagram diagram = flowEngine.generate(view);

            return switch (format.toLowerCase()) {
                case "json" -> ResponseEntity.ok(diagram.toMap());
                case "mermaid" -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_PLAIN)
                        .body(flowEngine.render(diagram, "mermaid"));
                case "html" -> ResponseEntity.ok()
                        .contentType(MediaType.TEXT_HTML)
                        .body(flowEngine.renderInteractive("Project"));
                default -> throw new IllegalArgumentException(
                        "Unknown format: " + format + ". Available: json, mermaid, html");
            };
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Drill down into a node within a view -- returns the child view's diagram.
     */
    @GetMapping("/{view}/{nodeId}/children")
    public Map<String, Object> getChildren(
            @PathVariable String view,
            @PathVariable String nodeId) {
        Map<String, Object> children = flowEngine.getChildren(view, nodeId);
        if (children == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No drill-down available for node " + nodeId + " in view " + view);
        }
        return children;
    }

    /**
     * Drill up from a node -- returns the parent context.
     */
    @GetMapping("/{view}/{nodeId}/parent")
    public Map<String, Object> getParent(
            @PathVariable String view,
            @PathVariable String nodeId) {
        Map<String, Object> parent = flowEngine.getParentContext(nodeId);
        if (parent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No parent context found for node " + nodeId);
        }
        return parent;
    }
}
