package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.analyzer.AnalysisResult;
import io.github.randomcodespace.iq.analyzer.Analyzer;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Scan a codebase and build a knowledge graph.
 */
@Component
@Command(name = "analyze", mixinStandardHelpOptions = true,
        description = "Scan codebase and build knowledge graph")
public class AnalyzeCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Path to codebase root")
    private Path path;

    @Option(names = {"--no-cache"}, description = "Skip incremental cache")
    private boolean noCache;

    private final Analyzer analyzer;
    private final CodeIqConfig config;

    public AnalyzeCommand(Analyzer analyzer, CodeIqConfig config) {
        this.analyzer = analyzer;
        this.config = config;
    }

    @Override
    public Integer call() {
        Path root = path.toAbsolutePath().normalize();
        CliOutput.step("\uD83D\uDD0D", "Scanning " + root + " ...");

        AnalysisResult result = analyzer.run(root, msg -> {
            if (msg.startsWith("Discovering")) {
                CliOutput.step("\uD83D\uDD0D", msg);
            } else if (msg.startsWith("Found")) {
                CliOutput.step("\uD83D\uDCC1", "@|cyan " + msg + "|@");
            } else if (msg.startsWith("Analyzing")) {
                CliOutput.step("\u2699\uFE0F", msg);
            } else if (msg.startsWith("Linking")) {
                CliOutput.step("\uD83D\uDD17", msg);
            } else if (msg.startsWith("Building")) {
                CliOutput.step("\uD83C\uDFD7\uFE0F", msg);
            } else if (msg.startsWith("Classifying")) {
                CliOutput.step("\uD83C\uDFF7\uFE0F", msg);
            } else if (msg.startsWith("Analysis complete")) {
                // handled below
            } else {
                CliOutput.info(msg);
            }
        });

        System.out.println();
        CliOutput.success("\u2705 Analysis complete");
        System.out.println();
        CliOutput.info("  Files discovered: " + result.totalFiles());
        CliOutput.info("  Files analyzed:   " + result.filesAnalyzed());
        CliOutput.cyan("  Nodes:            " + result.nodeCount());
        CliOutput.cyan("  Edges:            " + result.edgeCount());
        CliOutput.info("  Duration:         " + result.elapsed().toMillis() + " ms");

        if (!result.languageBreakdown().isEmpty()) {
            System.out.println();
            CliOutput.bold("  Languages:");
            result.languageBreakdown().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> CliOutput.info("    " + e.getKey() + ": " + e.getValue()));
        }

        if (!result.nodeBreakdown().isEmpty()) {
            System.out.println();
            CliOutput.bold("  Node kinds:");
            result.nodeBreakdown().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> CliOutput.info("    " + e.getKey() + ": " + e.getValue()));
        }

        return 0;
    }
}
