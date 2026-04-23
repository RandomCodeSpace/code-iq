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

    @Option(names = {"--graph"}, description = "Path to shared graph directory (for multi-repo)")
    private Path graphDir;

    @Option(names = {"--service-name"}, description = "Service name tag for nodes (for multi-repo)")
    private String serviceName;

    @Option(names = {"--verbose", "-v"}, description = "Enable verbose per-file logging")
    private boolean verbose;

    private final Analyzer analyzer;
    private final CodeIqConfig config;

    public IndexCommand(Analyzer analyzer, CodeIqConfig config) {
        this.analyzer = analyzer;
        this.config = config;
    }

    @Override
    public Integer call() {
        if (verbose) {
            ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("io.github.randomcodespace.iq"))
                    .setLevel(ch.qos.logback.classic.Level.DEBUG);
        }

        Path root = path.toAbsolutePath().normalize();

        CliOutput.configureFromOptions(config, graphDir, serviceName);

        // Use configured batch size if not overridden on command line
        int effectiveBatchSize = batchSize > 0 ? batchSize : config.getBatchSize();

        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.US);
        int cores = parallelism != null ? parallelism : Runtime.getRuntime().availableProcessors();

        // --no-cache overrides --incremental and deletes existing cache DB
        boolean useIncremental = incremental && !noCache;
        if (noCache) {
            Path cacheDir = root.resolve(config.getCacheDir());
            if (java.nio.file.Files.exists(cacheDir)) {
                try {
                    try (var walk = java.nio.file.Files.walk(cacheDir)) {
                        var logger = org.slf4j.LoggerFactory.getLogger(IndexCommand.class);
                        walk.sorted(java.util.Comparator.reverseOrder())
                                .forEach(p -> {
                                    try {
                                        java.nio.file.Files.deleteIfExists(p);
                                    } catch (java.io.IOException ex) {
                                        logger.debug("Could not delete cache entry {}: {}", p, ex.getMessage());
                                    }
                                });
                    }
                    CliOutput.info("  Deleted existing cache at " + cacheDir);
                } catch (Exception e) {
                    CliOutput.warn("  Could not delete cache: " + e.getMessage());
                }
            }
        }

        CliOutput.step("[*]", "Indexing " + root + " ...");
        CliOutput.info("  (batch size: " + effectiveBatchSize + " files, "
                + cores + " cores, H2 store, config-first smart pipeline)");
        if (useIncremental) {
            CliOutput.info("  (incremental mode -- use --no-cache for full re-index)");
        }

        AnalysisResult result = analyzer.runSmartIndex(root, parallelism, effectiveBatchSize,
                useIncremental, msg -> {
            if (msg.startsWith("Phase 1")) {
                CliOutput.step("[*]", "@|bold " + msg + "|@");
            } else if (msg.startsWith("Phase 2")) {
                CliOutput.step("[+]", "@|bold " + msg + "|@");
            } else if (msg.startsWith("Processing module")) {
                CliOutput.step("[:]", msg);
            } else if (msg.startsWith("Processing batch")) {
                CliOutput.step("[~]", msg);
            } else if (msg.startsWith("Keyword filter")) {
                CliOutput.step("[!]", "@|green " + msg + "|@");
            } else if (msg.startsWith("Cache hits")) {
                CliOutput.step("[!]", "@|green " + msg + "|@");
            } else if (msg.startsWith("Service:")) {
                CliOutput.info("  " + msg);
            } else if (msg.startsWith("Smart index complete")) {
                // handled below
            } else {
                CliOutput.info(msg);
            }
        });

        CliOutput.printResultSummary(result, nf);
        CliOutput.info("  Store:   H2 (.code-iq/cache/analysis-cache)");

        System.out.println();
        CliOutput.info("  Next step: code-iq enrich " + root);

        return 0;
    }
}
