package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.analyzer.AnalysisResult;
import io.github.randomcodespace.iq.analyzer.Analyzer;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.config.ProjectConfigLoader;
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
 * Index a codebase: scan files, run detectors, write results to H2.
 * <p>
 * This command does NOT start Neo4j. It uses H2 as the primary store,
 * processing files in batches to keep memory bounded.
 * <p>
 * Use {@code enrich} after indexing to load data into Neo4j for graph queries.
 */
@Component
@Command(name = "index", mixinStandardHelpOptions = true,
        description = "Index codebase to H2 (no Neo4j, memory-efficient batched processing)")
public class IndexCommand implements Callable<Integer> {

    @Parameters(index = "0", defaultValue = ".", description = "Path to codebase root")
    private Path path;

    @Option(names = {"--no-cache"}, description = "Skip incremental cache (full re-index)")
    private boolean noCache;

    @Option(names = {"--incremental"}, defaultValue = "true", negatable = true,
            description = "Use incremental analysis (default: true)")
    private boolean incremental;

    @Option(names = {"--parallelism", "-p"},
            description = "Max parallel threads (default: auto-detect from CPU)")
    private Integer parallelism;

    @Option(names = {"--batch-size", "-b"}, defaultValue = "500",
            description = "Files per H2 flush batch (default: 500)")
    private int batchSize;

    private final Analyzer analyzer;
    private final CodeIqConfig config;

    public IndexCommand(Analyzer analyzer, CodeIqConfig config) {
        this.analyzer = analyzer;
        this.config = config;
    }

    @Override
    public Integer call() {
        Path root = path.toAbsolutePath().normalize();

        // Load project-level config overrides from .osscodeiq.yml if present
        ProjectConfigLoader.loadIfPresent(root, config);

        // Use configured batch size if not overridden on command line
        int effectiveBatchSize = batchSize > 0 ? batchSize : config.getBatchSize();

        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        int cores = parallelism != null ? parallelism : Runtime.getRuntime().availableProcessors();

        // --no-cache overrides --incremental
        boolean useIncremental = incremental && !noCache;

        CliOutput.step("\uD83D\uDD0D", "Indexing " + root + " ...");
        CliOutput.info("  (batch size: " + effectiveBatchSize + " files, "
                + cores + " cores, H2 store)");
        if (useIncremental) {
            CliOutput.info("  (incremental mode -- use --no-cache for full re-index)");
        }

        AnalysisResult result = analyzer.runBatchedIndex(root, parallelism, effectiveBatchSize,
                useIncremental, msg -> {
            if (msg.startsWith("Discovering")) {
                CliOutput.step("\uD83D\uDD0D", msg);
            } else if (msg.startsWith("Found")) {
                CliOutput.step("\uD83D\uDCC1", "@|cyan " + msg + "|@");
            } else if (msg.startsWith("Indexing") || msg.startsWith("Processing batch")) {
                CliOutput.step("\u2699\uFE0F", msg);
            } else if (msg.startsWith("Cache hits")) {
                CliOutput.step("\u26A1", "@|green " + msg + "|@");
            } else if (msg.startsWith("Index complete")) {
                // handled below
            } else {
                CliOutput.info(msg);
            }
        });

        long secs = result.elapsed().toSeconds();
        String timeStr = secs > 0 ? secs + "s" : result.elapsed().toMillis() + "ms";

        System.out.println();
        CliOutput.success("\u2705 Index complete -- "
                + nf.format(result.nodeCount()) + " nodes, "
                + nf.format(result.edgeCount()) + " edges in " + timeStr);
        System.out.println();
        CliOutput.info("  Files:   " + nf.format(result.totalFiles()) + " discovered, "
                + nf.format(result.filesAnalyzed()) + " analyzed");
        CliOutput.cyan("  Nodes:   " + nf.format(result.nodeCount()));
        CliOutput.cyan("  Edges:   " + nf.format(result.edgeCount()));
        CliOutput.info("  Time:    " + timeStr);
        CliOutput.info("  Store:   H2 (.code-intelligence/analysis-cache)");

        if (!result.nodeBreakdown().isEmpty()) {
            System.out.println();
            StringBuilder topNodes = new StringBuilder("  Top node kinds: ");
            result.nodeBreakdown().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> topNodes.append(e.getKey()).append(" (")
                            .append(nf.format(e.getValue())).append("), "));
            if (topNodes.length() > 2) topNodes.setLength(topNodes.length() - 2);
            CliOutput.info(topNodes.toString());
        }

        if (!result.languageBreakdown().isEmpty()) {
            StringBuilder langs = new StringBuilder("  Languages: ");
            result.languageBreakdown().entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(10)
                    .forEach(e -> langs.append(e.getKey()).append(" (")
                            .append(nf.format(e.getValue())).append("), "));
            if (langs.length() > 2) langs.setLength(langs.length() - 2);
            CliOutput.info(langs.toString());
        }

        System.out.println();
        CliOutput.info("  Next step: code-iq enrich " + root);

        return 0;
    }
}
