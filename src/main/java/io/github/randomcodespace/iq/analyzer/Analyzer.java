package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.analyzer.linker.Linker;
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.cache.FileHasher;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.detector.Detector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorRegistry;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorUtils;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.model.CodeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/**
 * Main analysis pipeline orchestrator.
 * <p>
 * Steps:
 * <ol>
 *   <li>Discover files (FileDiscovery)</li>
 *   <li>For each file (virtual threads): read, parse, run detectors</li>
 *   <li>Build graph (batched via GraphBuilder)</li>
 *   <li>Run cross-file linkers</li>
 *   <li>Classify layers</li>
 *   <li>Return AnalysisResult</li>
 * </ol>
 * <p>
 * Determinism: files are sorted before processing and results are
 * collected in indexed slots to avoid ordering non-determinism from
 * parallel execution.
 */
@Service
public class Analyzer {

    private static final Logger log = LoggerFactory.getLogger(Analyzer.class);

    /** Languages whose content should be fed through the structured parser. */
    private static final Set<String> STRUCTURED_LANGUAGES = Set.of(
            "yaml", "json", "xml", "toml", "ini", "properties"
    );

    private final DetectorRegistry registry;
    private final StructuredParser parser;
    private final FileDiscovery fileDiscovery;
    private final LayerClassifier layerClassifier;
    private final List<Linker> linkers;
    private final CodeIqConfig config;

    public Analyzer(
            DetectorRegistry registry,
            StructuredParser parser,
            FileDiscovery fileDiscovery,
            LayerClassifier layerClassifier,
            List<Linker> linkers,
            CodeIqConfig config
    ) {
        this.registry = registry;
        this.parser = parser;
        this.fileDiscovery = fileDiscovery;
        this.layerClassifier = layerClassifier;
        this.linkers = linkers;
        this.config = config;
    }

    /**
     * Execute the analysis pipeline on the given repository path.
     *
     * @param repoPath    root of the repository to analyze
     * @param onProgress  optional callback for progress reporting (may be null)
     * @return the analysis result containing graph data and statistics
     */
    public AnalysisResult run(Path repoPath, Consumer<String> onProgress) {
        return run(repoPath, null, onProgress);
    }

    /**
     * Execute the analysis pipeline with optional parallelism control.
     *
     * @param repoPath     root of the repository to analyze
     * @param parallelism  max parallel threads, or null for adaptive (virtual threads)
     * @param onProgress   optional callback for progress reporting (may be null)
     * @return the analysis result containing graph data and statistics
     */
    public AnalysisResult run(Path repoPath, Integer parallelism, Consumer<String> onProgress) {
        return run(repoPath, parallelism, true, onProgress);
    }

    /**
     * Execute the analysis pipeline with incremental analysis support.
     *
     * @param repoPath     root of the repository to analyze
     * @param parallelism  max parallel threads, or null for adaptive (virtual threads)
     * @param incremental  if true, use file content hashing to skip unchanged files
     * @param onProgress   optional callback for progress reporting (may be null)
     * @return the analysis result containing graph data and statistics
     */
    public AnalysisResult run(Path repoPath, Integer parallelism, boolean incremental,
                              Consumer<String> onProgress) {
        Instant start = Instant.now();
        Consumer<String> report = onProgress != null ? onProgress : msg -> {};

        final Path root = repoPath.toAbsolutePath().normalize();

        // Open incremental cache if enabled
        AnalysisCache cache = null;
        if (incremental) {
            try {
                Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
                cache = new AnalysisCache(cachePath);
                report.accept("Incremental analysis enabled");
            } catch (Exception e) {
                log.debug("Could not open incremental cache, running full analysis", e);
            }
        }

        try {
            return runWithCache(root, parallelism, cache, report, start);
        } finally {
            if (cache != null) {
                cache.close();
            }
        }
    }

    private AnalysisResult runWithCache(Path root, Integer parallelism, AnalysisCache cache,
                                         Consumer<String> report, Instant start) {
        // 1. Discover files
        report.accept("Discovering files...");
        List<DiscoveredFile> files = fileDiscovery.discover(root);
        int totalFiles = files.size();
        report.accept("Found " + totalFiles + " files");

        // Compute language breakdown
        Map<String, Integer> languageBreakdown = new HashMap<>();
        for (DiscoveredFile f : files) {
            languageBreakdown.merge(f.language(), 1, Integer::sum);
        }

        // 2. Analyze files in parallel with virtual threads
        report.accept("Analyzing " + totalFiles + " files...");
        DetectorResult[] resultSlots = new DetectorResult[files.size()];
        int[] cacheHits = {0};

        var executorService = parallelism != null && parallelism > 0
                ? Executors.newFixedThreadPool(parallelism)
                : Executors.newVirtualThreadPerTaskExecutor();
        try (var executor = executorService) {
            List<Future<?>> futures = new ArrayList<>(files.size());
            for (int i = 0; i < files.size(); i++) {
                final int idx = i;
                final DiscoveredFile file = files.get(idx);
                final AnalysisCache cacheRef = cache;
                futures.add(executor.submit(() -> {
                    // Check cache first
                    if (cacheRef != null) {
                        try {
                            Path absPath = root.resolve(file.path());
                            String hash = FileHasher.hash(absPath);
                            if (cacheRef.isCached(hash)) {
                                var cached = cacheRef.loadCachedResults(hash);
                                if (cached != null) {
                                    resultSlots[idx] = DetectorResult.of(cached.nodes(), cached.edges());
                                    synchronized (cacheHits) {
                                        cacheHits[0]++;
                                    }
                                    return null;
                                }
                            }

                            // Run detectors and cache result
                            DetectorResult result = analyzeFile(file, root);
                            resultSlots[idx] = result;
                            if (result != null && (!result.nodes().isEmpty() || !result.edges().isEmpty())) {
                                cacheRef.storeResults(hash, file.path().toString(), file.language(),
                                        result.nodes(), result.edges());
                            }
                        } catch (IOException e) {
                            log.debug("Could not hash file {}", file.path(), e);
                            resultSlots[idx] = analyzeFile(file, root);
                        }
                    } else {
                        resultSlots[idx] = analyzeFile(file, root);
                    }
                    return null;
                }));
            }

            // Collect in order -- deterministic regardless of thread completion order
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get();
                } catch (ExecutionException e) {
                    log.warn("Analysis failed for {}", files.get(i).path(), e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Analysis interrupted for {}", files.get(i).path());
                }
            }
        }

        if (cache != null && cacheHits[0] > 0) {
            report.accept("Cache hits: " + cacheHits[0] + " / " + totalFiles + " files");
        }

        // 3. Build graph (batched)
        report.accept("Building graph...");
        var builder = new GraphBuilder();
        int filesAnalyzed = 0;
        for (int i = 0; i < resultSlots.length; i++) {
            DetectorResult result = resultSlots[i];
            if (result != null && (!result.nodes().isEmpty() || !result.edges().isEmpty())) {
                builder.addResult(result);
                filesAnalyzed++;
            }
        }

        // 4. Run cross-file linkers
        report.accept("Linking cross-file relationships...");
        builder.runLinkers(linkers);

        // Flush and collect deferred edges
        GraphBuilder.FlushResult flushed = builder.flush();
        List<io.github.randomcodespace.iq.model.CodeEdge> recoveredEdges = builder.flushDeferred();

        // 5. Classify layers
        report.accept("Classifying layers...");
        List<CodeNode> allNodes = builder.getNodes();
        layerClassifier.classify(allNodes);

        // 6. Compute node breakdown
        Map<String, Integer> nodeBreakdown = new HashMap<>();
        for (CodeNode node : allNodes) {
            String kindValue = node.getKind().getValue();
            nodeBreakdown.merge(kindValue, 1, Integer::sum);
        }

        // 7. Compute edge breakdown
        Map<String, Integer> edgeBreakdown = new HashMap<>();
        for (var edge : builder.getEdges()) {
            String kindValue = edge.getKind().getValue();
            edgeBreakdown.merge(kindValue, 1, Integer::sum);
        }

        // 7b. Compute framework breakdown from node properties
        Map<String, Integer> frameworkBreakdown = new HashMap<>();
        for (CodeNode node : allNodes) {
            Object fw = node.getProperties().get("framework");
            if (fw != null && !fw.toString().isEmpty()) {
                frameworkBreakdown.merge(fw.toString(), 1, Integer::sum);
            }
            Object authType = node.getProperties().get("auth_type");
            if (authType != null && !authType.toString().isEmpty()) {
                frameworkBreakdown.merge("auth:" + authType, 1, Integer::sum);
            }
        }

        // 8. Record analysis run in cache
        if (cache != null) {
            String commitSha = getGitHead(root);
            cache.recordRun(commitSha, filesAnalyzed);
        }

        Duration elapsed = Duration.between(start, Instant.now());
        int nodeCount = builder.getNodeCount();
        int edgeCount = builder.getEdgeCount();

        report.accept("Analysis complete - " + nodeCount + " nodes, " + edgeCount + " edges");
        log.debug("Analysis complete: {} nodes, {} edges in {}ms",
                nodeCount, edgeCount, elapsed.toMillis());

        return new AnalysisResult(
                totalFiles,
                filesAnalyzed,
                nodeCount,
                edgeCount,
                languageBreakdown,
                nodeBreakdown,
                edgeBreakdown,
                frameworkBreakdown,
                elapsed
        );
    }

    /**
     * Analyze a single file: read content, parse if structured, run matching detectors.
     */
    DetectorResult analyzeFile(DiscoveredFile file, Path repoPath) {
        Instant fileStart = Instant.now();
        Path absPath = repoPath.resolve(file.path());

        // Read file content
        String content;
        try {
            byte[] raw = Files.readAllBytes(absPath);
            content = DetectorUtils.decodeContent(raw);
        } catch (IOException e) {
            log.debug("Could not read file: {}", absPath, e);
            return DetectorResult.empty();
        }

        // Parse structured data if applicable
        Object parsedData = null;
        if (STRUCTURED_LANGUAGES.contains(file.language())) {
            parsedData = parser.parse(file.language(), content, file.path().toString());
        }

        // Derive module name
        String moduleName = DetectorUtils.deriveModuleName(file.path().toString(), file.language());

        // Create context
        var ctx = new DetectorContext(
                file.path().toString(),
                file.language(),
                content,
                parsedData,
                moduleName
        );

        // Run matching detectors and merge results
        List<Detector> detectors = registry.detectorsForLanguage(file.language());
        if (detectors.isEmpty()) {
            return DetectorResult.empty();
        }

        var allNodes = new ArrayList<CodeNode>();
        var allEdges = new ArrayList<io.github.randomcodespace.iq.model.CodeEdge>();

        for (Detector detector : detectors) {
            try {
                Instant detStart = Instant.now();
                DetectorResult result = detector.detect(ctx);
                long detMs = Duration.between(detStart, Instant.now()).toMillis();
                if (detMs > 100) {
                    log.debug("Slow detector {} on {} ({} bytes): {}ms",
                            detector.getName(), file.path(), content.length(), detMs);
                }
                allNodes.addAll(result.nodes());
                allEdges.addAll(result.edges());
            } catch (Exception e) {
                log.debug("Detector {} failed on {}: {}",
                        detector.getName(), file.path(), e.getMessage());
            }
        }

        // Clear ANTLR parse cache after all detectors have run for this file
        AntlrParserFactory.clearCache();

        long fileMs = Duration.between(fileStart, Instant.now()).toMillis();
        if (fileMs > 500) {
            log.debug("Slow file {} ({}): {}ms", file.path(), file.language(), fileMs);
        }

        // Set module on all nodes that don't have one yet
        if (moduleName != null) {
            for (CodeNode node : allNodes) {
                if (node.getModule() == null || node.getModule().isEmpty()) {
                    node.setModule(moduleName);
                }
            }
        }

        return DetectorResult.of(allNodes, allEdges);
    }

    /**
     * Get the current git HEAD commit SHA, or null if not a git repo.
     */
    private String getGitHead(Path repoPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("git", "rev-parse", "HEAD")
                    .directory(repoPath.toFile())
                    .redirectErrorStream(true);
            Process proc = pb.start();
            String sha = new String(proc.getInputStream().readAllBytes()).trim();
            int exitCode = proc.waitFor();
            if (exitCode == 0 && sha.length() >= 7) {
                return sha;
            }
        } catch (Exception e) {
            log.debug("Could not determine git HEAD", e);
        }
        return null;
    }
}
