package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

/**
 * Generate architecture flow diagrams from the knowledge graph.
 */
@Component
@Command(name = "flow", mixinStandardHelpOptions = true,
        description = "Generate architecture flow diagrams")
public class FlowCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Path to analyzed codebase")
    private Path path;

    @Option(names = {"--view", "-v"}, defaultValue = "overview",
            description = "View: overview, layers, kinds (default: overview)")
    private String view;

    @Option(names = {"--format", "-f"}, defaultValue = "mermaid",
            description = "Output format: mermaid, json (default: mermaid)")
    private String format;

    @Option(names = {"--output", "-o"}, description = "Output file (stdout if omitted)")
    private Path output;

    private final GraphStore graphStore;

    public FlowCommand(GraphStore graphStore) {
        this.graphStore = graphStore;
    }

    @Override
    public Integer call() {
        List<CodeNode> allNodes = graphStore.findAllPaginated(0, 1000);

        if (allNodes.isEmpty()) {
            CliOutput.warn("No graph data found. Run 'code-iq analyze' first.");
            return 1;
        }

        String content = switch (view.toLowerCase()) {
            case "layers" -> generateLayerView(allNodes);
            case "kinds" -> generateKindView(allNodes);
            default -> generateOverview(allNodes);
        };

        if (output != null) {
            try {
                Files.writeString(output, content, StandardCharsets.UTF_8);
                CliOutput.success("Flow diagram exported to " + output);
            } catch (IOException e) {
                CliOutput.error("Failed to write output: " + e.getMessage());
                return 1;
            }
        } else {
            System.out.println(content);
        }

        return 0;
    }

    private String generateOverview(List<CodeNode> nodes) {
        if ("json".equalsIgnoreCase(format)) {
            return generateOverviewJson(nodes);
        }
        var sb = new StringBuilder("graph TD\n");
        Map<String, List<CodeNode>> byLayer = nodes.stream()
                .filter(n -> n.getLayer() != null)
                .collect(Collectors.groupingBy(CodeNode::getLayer));

        for (var entry : byLayer.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            sb.append("    subgraph ").append(entry.getKey()).append("\n");
            for (CodeNode node : entry.getValue().stream().limit(20).toList()) {
                sb.append("        ").append(mermaidId(node.getId()))
                        .append("[\"").append(node.getLabel()).append("\"]\n");
            }
            sb.append("    end\n");
        }
        return sb.toString();
    }

    private String generateOverviewJson(List<CodeNode> nodes) {
        Map<String, Long> byLayer = nodes.stream()
                .filter(n -> n.getLayer() != null)
                .collect(Collectors.groupingBy(CodeNode::getLayer, Collectors.counting()));
        Map<String, Long> byKind = nodes.stream()
                .collect(Collectors.groupingBy(
                        n -> n.getKind().getValue(), Collectors.counting()));

        var sb = new StringBuilder("{\n");
        sb.append("  \"view\": \"overview\",\n");
        sb.append("  \"total_nodes\": ").append(nodes.size()).append(",\n");
        sb.append("  \"by_layer\": {");
        sb.append(byLayer.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\": " + e.getValue())
                .collect(Collectors.joining(", ")));
        sb.append("},\n  \"by_kind\": {");
        sb.append(byKind.entrySet().stream()
                .map(e -> "\"" + e.getKey() + "\": " + e.getValue())
                .collect(Collectors.joining(", ")));
        sb.append("}\n}");
        return sb.toString();
    }

    private String generateLayerView(List<CodeNode> nodes) {
        if ("json".equalsIgnoreCase(format)) {
            return generateOverviewJson(nodes);
        }
        var sb = new StringBuilder("graph LR\n");
        sb.append("    frontend[Frontend] --> backend[Backend]\n");
        sb.append("    backend --> infra[Infrastructure]\n");
        sb.append("    shared[Shared] -.-> frontend\n");
        sb.append("    shared -.-> backend\n");

        Map<String, Long> counts = nodes.stream()
                .filter(n -> n.getLayer() != null)
                .collect(Collectors.groupingBy(CodeNode::getLayer, Collectors.counting()));
        for (var entry : counts.entrySet()) {
            sb.append("    %% ").append(entry.getKey())
                    .append(": ").append(entry.getValue()).append(" nodes\n");
        }
        return sb.toString();
    }

    private String generateKindView(List<CodeNode> nodes) {
        if ("json".equalsIgnoreCase(format)) {
            return generateOverviewJson(nodes);
        }
        var sb = new StringBuilder("graph TD\n");
        Map<String, Long> counts = nodes.stream()
                .collect(Collectors.groupingBy(
                        n -> n.getKind().getValue(), Collectors.counting()));
        for (var entry : counts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(15).toList()) {
            sb.append("    ").append(mermaidId(entry.getKey()))
                    .append("[\"").append(entry.getKey())
                    .append(" (").append(entry.getValue()).append(")\"]\n");
        }
        return sb.toString();
    }

    private static String mermaidId(String id) {
        return id.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
