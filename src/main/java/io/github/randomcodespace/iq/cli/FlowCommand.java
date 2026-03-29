package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.flow.FlowModels.FlowDiagram;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/**
 * Generate architecture flow diagrams from the knowledge graph.
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

    @Autowired
    public FlowCommand(Optional<FlowEngine> flowEngine) {
        this.flowEngine = flowEngine.orElse(null);
    }

    /** Convenience constructor for tests. */
    FlowCommand(FlowEngine flowEngine) {
        this.flowEngine = flowEngine;
    }

    @Override
    public Integer call() {
        if (flowEngine == null) {
            CliOutput.error("Flow diagrams require Neo4j. Run 'code-iq enrich' first, then 'code-iq serve'.");
            return 1;
        }
        try {
            String content;

            if ("html".equalsIgnoreCase(format)) {
                String projectName = path.toAbsolutePath().getFileName().toString();
                content = flowEngine.renderInteractive(projectName);
            } else {
                FlowDiagram diagram = flowEngine.generate(view.toLowerCase());
                content = flowEngine.render(diagram, format.toLowerCase());
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
}
