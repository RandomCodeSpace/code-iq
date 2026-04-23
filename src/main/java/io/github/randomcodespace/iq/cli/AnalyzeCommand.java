package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.analyzer.AnalysisResult;
import io.github.randomcodespace.iq.analyzer.Analyzer;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Scan a codebase and build a knowledge graph.
 * <p>
 * This is the legacy command that uses in-memory graph building with Neo4j.
 * For memory-efficient indexing, use {@code index} instead.
 * Kept as backward-compatible alias.
 */
@Component
@Command(name = "analyze", mixinStandardHelpOptions = true,
        description = "Scan codebase and build knowledge graph (legacy; prefer 'index' for large codebases)")
public class AnalyzeCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Path to codebase root")
    private Path path;

    @Option(names = {"--no-cache"}, description = "Skip incremental cache (full re-analysis)")
    private boolean noCache;

    @Option(names = {"--incremental"}, defaultValue = "true", negatable = true,
            description = "Use incremental analysis (default: true)")
    private boolean incremental;

    @Option(names = {"--parallelism", "-p"},
            description = "Max parallel threads (default: auto-detect from CPU)")
    private Integer parallelism;

    @Option(names = {"--graph"}, description = "Path to shared graph directory (for multi-repo)")
    private Path graphDir;

    @Option(names = {"--service-name"}, description = "Service name tag for nodes (for multi-repo)")
    private String serviceName;

    private final Analyzer analyzer;
    private final CodeIqConfig config;

    public AnalyzeCommand(Analyzer analyzer, CodeIqConfig config) {
        this.analyzer = analyzer;
        this.config = config;
    }

    @Override
    public Integer call() {
        Path root = path.toAbsolutePath().normalize();

        CliOutput.configureFromOptions(config, graphDir, serviceName);

        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        int cores = parallelism != null ? parallelism : Runtime.getRuntime().availableProcessors();

        // --no-cache overrides --incremental
        boolean useIncremental = incremental && !noCache;

        CliOutput.step("[*]", "Scanning " + root + " ...");
        if (useIncremental) {
            CliOutput.info("  (incremental mode -- use --no-cache for full re-analysis)");
        }

        AnalysisResult result = analyzer.run(root, parallelism, useIncremental, msg -> {
            if (msg.startsWith("Discovering")) {
                CliOutput.step("[*]", msg);
            } else if (msg.startsWith("Found")) {
                CliOutput.step("[+]", "@|cyan " + msg + "|@");
            } else if (msg.startsWith("Analyzing")) {
                CliOutput.step("[~]", msg.replace("files...", "files using " + cores + " cores..."));
            } else if (msg.startsWith("Building")) {
                CliOutput.step("[^]", msg);
            } else if (msg.startsWith("Linking")) {
                CliOutput.step("[-]", msg);
            } else if (msg.startsWith("Classifying")) {
                CliOutput.step("[#]", msg);
            } else if (msg.startsWith("Cache hits")) {
                CliOutput.step("[!]", "@|green " + msg + "|@");
            } else if (msg.startsWith("Incremental")) {
                CliOutput.step("[!]", msg);
            } else if (msg.startsWith("Analysis complete")) {
                // handled below
            } else {
                CliOutput.info(msg);
            }
        });

        CliOutput.printResultSummary(result, nf);

        if (result.frameworkBreakdown() != null && !result.frameworkBreakdown().isEmpty()) {
            StringBuilder fws = new StringBuilder("  Frameworks: ");
            result.frameworkBreakdown().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(15)
                    .forEach(e -> fws.append(e.getKey()).append(" (")
                            .append(nf.format(e.getValue())).append("), "));
            if (fws.length() > 2) fws.setLength(fws.length() - 2);
            CliOutput.info(fws.toString());
        }

        return 0;
    }
}
