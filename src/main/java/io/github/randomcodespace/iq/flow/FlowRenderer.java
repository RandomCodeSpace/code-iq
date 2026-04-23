package io.github.randomcodespace.iq.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.randomcodespace.iq.flow.FlowModels.FlowDiagram;
import io.github.randomcodespace.iq.flow.FlowModels.FlowEdge;
import io.github.randomcodespace.iq.flow.FlowModels.FlowNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Renderers for flow diagrams -- Mermaid, JSON, and interactive HTML.
 * Mirrors the Python renderer exactly.
 */
public final class FlowRenderer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // Node shapes by kind
    private static final Map<String, String[]> SHAPES = Map.ofEntries(
            Map.entry("trigger", new String[]{"([", "])" }),
            Map.entry("pipeline", new String[]{"[", "]"}),
            Map.entry("job", new String[]{"[", "]"}),
            Map.entry("endpoint", new String[]{"{{", "}}"}),
            Map.entry("entity", new String[]{"[(", ")]"}),
            Map.entry("database", new String[]{"[(", ")]"}),
            Map.entry("guard", new String[]{">", "]"}),
            Map.entry("middleware", new String[]{">", "]"}),
            Map.entry("component", new String[]{"([", "])"}),
            Map.entry("messaging", new String[]{"[/", "\\]"}),
            Map.entry("k8s", new String[]{"[", "]"}),
            Map.entry("docker", new String[]{"[", "]"}),
            Map.entry("terraform", new String[]{"[", "]"}),
            Map.entry("infra", new String[]{"[", "]"}),
            Map.entry("code", new String[]{"[", "]"}),
            Map.entry("service", new String[]{"[", "]"})
    );

    private static final Map<String, String> EDGE_STYLES = Map.of(
            "solid", "-->",
            "dotted", "-.->",
            "thick", "==>"
    );

    private static final Map<String, String> STYLE_CLASSES = Map.of(
            "success", ":::success",
            "warning", ":::warning",
            "danger", ":::danger",
            "default", ""
    );

    private FlowRenderer() {
    }

    /**
     * Render a FlowDiagram as a Mermaid flowchart string.
     */
    public static String renderMermaid(FlowDiagram diagram) {
        var sb = new StringBuilder();
        sb.append("graph ").append(diagram.direction()).append('\n');

        // Style definitions
        sb.append("    classDef success fill:#d4edda,stroke:#28a745,color:#155724\n");
        sb.append("    classDef warning fill:#fff3cd,stroke:#ffc107,color:#856404\n");
        sb.append("    classDef danger fill:#f8d7da,stroke:#dc3545,color:#721c24\n");
        sb.append('\n');

        for (var sg : diagram.subgraphs()) {
            String sgId = sanitizeId(sg.id());
            sb.append("    subgraph ").append(sgId).append("[\"").append(escapeLabel(sg.label())).append("\"]\n");
            var sortedNodes = sg.nodes().stream()
                    .sorted(Comparator.comparing(FlowNode::id))
                    .toList();
            for (var node : sortedNodes) {
                appendNodeLine(sb, node, "        ");
            }
            sb.append("    end\n");
            sb.append('\n');
        }

        var sortedLoose = diagram.looseNodes().stream()
                .sorted(Comparator.comparing(FlowNode::id))
                .toList();
        for (var node : sortedLoose) {
            appendNodeLine(sb, node, "    ");
        }

        sb.append('\n');
        var sortedEdges = diagram.edges().stream()
                .sorted(Comparator.comparing(FlowEdge::source).thenComparing(FlowEdge::target))
                .toList();
        for (var edge : sortedEdges) {
            String src = sanitizeId(edge.source());
            String tgt = sanitizeId(edge.target());
            String arrow = EDGE_STYLES.getOrDefault(edge.style(), "-->");
            if (edge.label() != null) {
                sb.append("    ").append(src).append(' ').append(arrow)
                        .append('|').append(escapeLabel(edge.label())).append("| ").append(tgt).append('\n');
            } else {
                sb.append("    ").append(src).append(' ').append(arrow).append(' ').append(tgt).append('\n');
            }
        }

        return sb.toString();
    }

    /**
     * Render a FlowDiagram as a JSON string.
     */
    public static String renderJson(FlowDiagram diagram) {
        try {
            return MAPPER.writeValueAsString(diagram.toMap());
        } catch (Exception e) {
            throw new RuntimeException("Failed to render JSON", e);
        }
    }

    /**
     * Render all views into a self-contained interactive HTML file.
     */
    public static String renderHtml(Map<String, FlowDiagram> views, Map<String, Object> stats,
                                     String projectName) {
        var viewsData = new TreeMap<String, Object>();
        for (var entry : views.entrySet()) {
            viewsData.put(entry.getKey(), entry.getValue().toMap());
        }

        String template = loadResource("/templates/flow/interactive.html");

        // Inline vendor JS for offline/firewall use
        template = template.replace("{{VENDOR_DAGRE}}", loadResource("/static/js/vendor/dagre.min.js"));
        template = template.replace("{{VENDOR_CYTOSCAPE}}", loadResource("/static/js/vendor/cytoscape.min.js"));
        template = template.replace("{{VENDOR_CYTOSCAPE_DAGRE}}", loadResource("/static/js/vendor/cytoscape-dagre.min.js"));

        try {
            template = template.replace("{{VIEWS_DATA}}", MAPPER.writeValueAsString(viewsData));
            template = template.replace("{{STATS}}", MAPPER.writeValueAsString(stats));
            template = template.replace("{{PROJECT_NAME}}", MAPPER.writeValueAsString(projectName));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize data for HTML template", e);
        }

        return template;
    }

    // --- Private helpers ---

    private static void appendNodeLine(StringBuilder sb, FlowNode node, String indent) {
        String nid = sanitizeId(node.id());
        String label = escapeLabel(node.label());
        String[] brackets = SHAPES.getOrDefault(node.kind(), new String[]{"[", "]"});
        String styleClass = STYLE_CLASSES.getOrDefault(node.style(), "");
        sb.append(indent).append(nid).append(brackets[0]).append('"').append(label).append('"')
                .append(brackets[1]).append(styleClass).append('\n');
    }

    static String sanitizeId(String raw) {
        return raw.replaceAll("\\W", "_");
    }

    static String escapeLabel(String text) {
        if (text == null) return "";
        // Process '#' first so that '&#' sequences generated by later replacements
        // are not double-escaped (same order issue exists in Python, but we fix it here).
        text = text.replace("#", "&#35;");
        for (char ch : new char[]{'"', '|', '[', ']', '{', '}', '(', ')', '<', '>'}) {
            text = text.replace(String.valueOf(ch), "&#" + (int) ch + ";");
        }
        return text;
    }

    private static String loadResource(String path) {
        try (InputStream is = FlowRenderer.class.getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load resource: " + path, e);
        }
    }
}
