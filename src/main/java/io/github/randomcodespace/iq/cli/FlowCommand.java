package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.flow.FlowModels.FlowDiagram;
import io.github.randomcodespace.iq.model.CodeNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Generate architecture flow diagrams from the knowledge graph.
 * Works from H2 cache (no Neo4j required).
 * Supports 5 views (overview, ci, deploy, runtime, auth) and 3 formats (mermaid, json, html).
 */
@Component
@Command(name = "flow", mixinStandardHelpOptions = true,
        description = "Generate architecture flow diagrams")
public class FlowCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Path to analyzed codebase")
    private Path path;

    @Option(names = {"--view", "-v"}, defaultValue = "overview",
            description = "View: overview, ci, deploy, runtime, auth (default: overview)")
    private String view;

    @Option(names = {"--format", "-f"}, defaultValue = "mermaid",
            description = "Output format: mermaid, json, html (default: mermaid)")
    private String format;

    @Option(names = {"--output", "-o"}, description = "Output file (stdout if omitted)")
    private Path output;

    private final FlowEngine flowEngine;
    private final CodeIqConfig config;

    /** No-arg constructor for Picocli direct instantiation. */
    public FlowCommand() {
        this.flowEngine = null;
        this.config = null;
    }

    @Autowired
    public FlowCommand(Optional<FlowEngine> flowEngine, CodeIqConfig config) {
        this.flowEngine = flowEngine.orElse(null);
        this.config = config;
    }

    /** Convenience constructor for tests. */
    FlowCommand(FlowEngine flowEngine, CodeIqConfig config) {
        this.flowEngine = flowEngine;
        this.config = config;
    }

    @Override
    public Integer call() {
        FlowEngine engine = resolveEngine();
        if (engine == null) {
            CliOutput.error("No analysis cache found. Run 'code-iq analyze' or 'code-iq index' first.");
            return 1;
        }

        try {
            String content;

            if ("html".equalsIgnoreCase(format)) {
                String projectName = java.util.Objects.toString(
                        path.toAbsolutePath().getFileName(), "flow");
                content = engine.renderInteractive(projectName);
            } else {
                FlowDiagram diagram = engine.generate(view.toLowerCase());
                content = engine.render(diagram, format.toLowerCase());
            }

            if (output != null) {
                Files.writeString(output, content, StandardCharsets.UTF_8);
                CliOutput.success("Flow diagram exported to " + output);
            } else {
                System.out.println(content);
            }

            return 0;
        } catch (IllegalArgumentException e) {
            CliOutput.error(e.getMessage());
            return 1;
        } catch (IOException e) {
            CliOutput.error("Failed to write output: " + e.getMessage());
            return 1;
        }
    }

    /**
     * Use the Spring-injected FlowEngine (backed by GraphStore/Neo4j) if available,
     * otherwise create one from H2 cache.
     */
    private FlowEngine resolveEngine() {
        if (flowEngine != null) {
            return flowEngine;
        }

        // Fall back to H2 cache
        if (config == null) return null;

        Path root = path.toAbsolutePath().normalize();
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        Path h2File = root.resolve(config.getCacheDir()).resolve("analysis-cache.mv.db");

        if (!Files.exists(h2File)) {
            return null;
        }

        try (AnalysisCache cache = new AnalysisCache(cachePath)) {
            List<CodeNode> nodes = cache.loadAllNodes();
            if (nodes.isEmpty()) return null;
            return FlowEngine.fromCache(nodes);
        }
    }
}
