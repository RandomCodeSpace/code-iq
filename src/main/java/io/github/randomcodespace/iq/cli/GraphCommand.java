package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeNode;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Export the knowledge graph in various formats.
 */
@Component
@Command(name = "graph", mixinStandardHelpOptions = true,
        description = "Export graph in various formats")
public class GraphCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Path to analyzed codebase")
    private Path path;

    @Option(names = {"--format", "-f"}, defaultValue = "json",
            description = "Output format: json, mermaid, dot (default: json)")
    private String format;

    @Option(names = {"--output", "-o"}, description = "Output file (stdout if omitted)")
    private Path output;

    @Option(names = {"--max-nodes"}, defaultValue = "500",
            description = "Maximum nodes to export (default: 500)")
    private int maxNodes;

    @Option(names = {"--focus"}, description = "Node ID to center export on")
    private String focus;

    @Option(names = {"--hops"}, defaultValue = "2",
            description = "Hops from focus node (default: 2)")
    private int hops;

    private final GraphStore graphStore;

    public GraphCommand(GraphStore graphStore) {
        this.graphStore = graphStore;
    }

    @Override
    public Integer call() {
        List<CodeNode> nodes;
        if (focus != null && !focus.isBlank()) {
            nodes = graphStore.findEgoGraph(focus, hops);
        } else {
            nodes = graphStore.findAllPaginated(0, maxNodes);
        }

        if (nodes.isEmpty()) {
            CliOutput.warn("No graph data found. Run 'code-iq analyze' first.");
            return 1;
        }

        String content = switch (format.toLowerCase()) {
            case "mermaid" -> renderMermaid(nodes);
            case "dot" -> renderDot(nodes);
            default -> renderJson(nodes);
        };

        if (output != null) {
            try {
                Files.writeString(output, content, StandardCharsets.UTF_8);
                CliOutput.success("Graph exported to " + output);
            } catch (IOException e) {
                CliOutput.error("Failed to write output: " + e.getMessage());
                return 1;
            }
        } else {
            System.out.println(content);
        }

        return 0;
    }

    private String renderJson(List<CodeNode> nodes) {
        var sb = new StringBuilder();
        sb.append("{\n  \"nodes\": [\n");
        for (int i = 0; i < nodes.size(); i++) {
            CodeNode n = nodes.get(i);
            sb.append("    {\"id\": \"").append(jsonEscape(n.getId()))
                    .append("\", \"kind\": \"").append(n.getKind().getValue())
                    .append("\", \"label\": \"").append(jsonEscape(n.getLabel()))
                    .append("\"}");
            if (i < nodes.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("  ],\n  \"count\": ").append(nodes.size()).append("\n}");
        return sb.toString();
    }

    private String renderMermaid(List<CodeNode> nodes) {
        var sb = new StringBuilder("graph TD\n");
        var nodeIds = nodes.stream().map(CodeNode::getId).collect(Collectors.toSet());
        for (CodeNode n : nodes) {
            String safeId = mermaidId(n.getId());
            sb.append("    ").append(safeId)
                    .append("[\"").append(n.getLabel()).append("\"]\n");
            for (var edge : n.getEdges()) {
                if (edge.getTarget() != null && nodeIds.contains(edge.getTarget().getId())) {
                    sb.append("    ").append(safeId)
                            .append(" -->|").append(edge.getKind().getValue())
                            .append("| ").append(mermaidId(edge.getTarget().getId()))
                            .append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String renderDot(List<CodeNode> nodes) {
        var sb = new StringBuilder("digraph G {\n  rankdir=LR;\n");
        var nodeIds = nodes.stream().map(CodeNode::getId).collect(Collectors.toSet());
        for (CodeNode n : nodes) {
            sb.append("  \"").append(dotEscape(n.getId()))
                    .append("\" [label=\"").append(dotEscape(n.getLabel()))
                    .append("\"];\n");
            for (var edge : n.getEdges()) {
                if (edge.getTarget() != null && nodeIds.contains(edge.getTarget().getId())) {
                    sb.append("  \"").append(dotEscape(n.getId()))
                            .append("\" -> \"").append(dotEscape(edge.getTarget().getId()))
                            .append("\" [label=\"").append(edge.getKind().getValue())
                            .append("\"];\n");
                }
            }
        }
        sb.append("}");
        return sb.toString();
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String dotEscape(String s) {
        if (s == null) return "";
        return s.replace("\"", "\\\"");
    }

    private static String mermaidId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
