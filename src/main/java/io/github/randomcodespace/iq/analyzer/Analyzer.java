package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.analyzer.linker.Linker;
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.cache.FileHasher;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.config.ProjectConfig;
import io.github.randomcodespace.iq.config.ProjectConfigLoader;
import io.github.randomcodespace.iq.detector.Detector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorRegistry;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorUtils;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
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

    /** Module boundary file names — presence signals a new module root. */
    static final Set<String> MODULE_BOUNDARY_MARKERS = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts",
            "package.json", "go.mod", "__init__.py",
            "Cargo.toml", "setup.py", "pyproject.toml"
    );

    private final DetectorRegistry registry;
    private final StructuredParser parser;
    private final FileDiscovery fileDiscovery;
    private final LayerClassifier layerClassifier;
    private final List<Linker> linkers;
    private final CodeIqConfig config;
    private final ConfigScanner configScanner;
    private final ArchitectureKeywordFilter keywordFilter;

    /** Primary constructor — used by Spring Boot dependency injection. */
    @Autowired
    public Analyzer(
            DetectorRegistry registry,
            StructuredParser parser,
            FileDiscovery fileDiscovery,
            LayerClassifier layerClassifier,
            List<Linker> linkers,
            CodeIqConfig config,
            ConfigScanner configScanner,
            ArchitectureKeywordFilter keywordFilter
    ) {
        this.registry = registry;
        this.parser = parser;
        this.fileDiscovery = fileDiscovery;
        this.layerClassifier = layerClassifier;
        this.linkers = linkers;
        this.config = config;
        this.configScanner = configScanner;
        this.keywordFilter = keywordFilter;
    }

    /** Backward-compatible constructor for tests that don't need smart indexing. */
    public Analyzer(
            DetectorRegistry registry,
            StructuredParser parser,
            FileDiscovery fileDiscovery,
            LayerClassifier layerClassifier,
            List<Linker> linkers,
            CodeIqConfig config
    ) {
        this(registry, parser, fileDiscovery, layerClassifier, linkers, config,
                new ConfigScanner(), new ArchitectureKeywordFilter());
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
        // 0. Load project config for pipeline filtering
        ProjectConfig projectConfig = ProjectConfigLoader.loadProjectConfig(root);
        DetectorRegistry effectiveRegistry = registry;

        // Apply detector category filter from project config
        if (projectConfig.hasDetectorCategoryFilter()) {
            effectiveRegistry = effectiveRegistry.filterByCategories(
                    projectConfig.getDetectorCategories());
            report.accept("Detector categories: " + projectConfig.getDetectorCategories());
        }

        // Apply detector include filter from project config
        if (projectConfig.hasDetectorIncludeFilter()) {
            effectiveRegistry = effectiveRegistry.filterByNames(
                    projectConfig.getDetectorInclude());
            report.accept("Detector include: " + projectConfig.getDetectorInclude());
        }

        // Apply parallelism override from project config
        if (parallelism == null && projectConfig.getPipelineParallelism() != null) {
            parallelism = projectConfig.getPipelineParallelism();
            report.accept("Pipeline parallelism: " + parallelism + " (from config)");
        }

        // 1. Discover files
        report.accept("Discovering files...");
        List<DiscoveredFile> files = fileDiscovery.discover(root);

        // Apply language filter from project config
        if (projectConfig.hasLanguageFilter()) {
            Set<String> allowedLanguages = new HashSet<>(projectConfig.getLanguages());
            files = files.stream()
                    .filter(f -> allowedLanguages.contains(f.language()))
                    .toList();
            report.accept("Language filter active: " + projectConfig.getLanguages());
        }

        // Apply exclude patterns from project config
        if (projectConfig.hasExcludePatterns()) {
            List<String> excludes = projectConfig.getExclude();
            List<java.util.regex.Pattern> compiledExcludes = compileExcludePatterns(excludes);
            files = files.stream()
                    .filter(f -> !matchesAnyCompiledExclude(f.path().toString(), compiledExcludes))
                    .toList();
            report.accept("Exclude patterns: " + excludes);
        }

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
        var cacheHitsCounter = new java.util.concurrent.atomic.AtomicInteger(0);

        final DetectorRegistry detectorRegistry = effectiveRegistry;
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
                                    cacheHitsCounter.incrementAndGet();
                                    return null;
                                }
                            }

                            // Run detectors and cache result
                            DetectorResult result = analyzeFile(file, root, detectorRegistry);
                            resultSlots[idx] = result;
                            if (result != null && (!result.nodes().isEmpty() || !result.edges().isEmpty())) {
                                cacheRef.storeResults(hash, file.path().toString(), file.language(),
                                        result.nodes(), result.edges());
                            }
                        } catch (IOException e) {
                            log.debug("Could not hash file {}", file.path(), e);
                            resultSlots[idx] = analyzeFile(file, root, detectorRegistry);
                        }
                    } else {
                        resultSlots[idx] = analyzeFile(file, root, detectorRegistry);
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

        if (cache != null && cacheHitsCounter.get() > 0) {
            report.accept("Cache hits: " + cacheHitsCounter.get() + " / " + totalFiles + " files");
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

        // 5b. Detect service boundaries and create SERVICE nodes
        report.accept("Detecting service boundaries...");
        var serviceDetector = new ServiceDetector();
        String projectDirName = root.getFileName() != null ? root.getFileName().toString() : "root";
        var serviceResult = serviceDetector.detect(allNodes, builder.getEdges(), projectDirName, root);
        if (!serviceResult.serviceNodes().isEmpty()) {
            builder.addNodes(serviceResult.serviceNodes());
            builder.addEdges(serviceResult.serviceEdges());
            allNodes = builder.getNodes(); // refresh reference after adding service nodes
        }

        // 5c. Tag nodes with service name if configured (multi-repo mode) -- overrides auto-detected
        String serviceName = config.getServiceName();
        if (serviceName != null && !serviceName.isBlank()) {
            for (CodeNode node : allNodes) {
                node.getProperties().put("service", serviceName);
            }
        }

        // 6. Attach edges to their source nodes for downstream consumers
        Map<String, CodeNode> nodeById = new HashMap<>(allNodes.size());
        for (CodeNode node : allNodes) {
            nodeById.put(node.getId(), node);
        }
        for (var edge : builder.getEdges()) {
            CodeNode source = nodeById.get(edge.getSourceId());
            if (source != null) {
                source.getEdges().add(edge);
            }
        }

        // 7. Compute node breakdown
        Map<String, Integer> nodeBreakdown = new HashMap<>();
        for (CodeNode node : allNodes) {
            String kindValue = node.getKind().getValue();
            nodeBreakdown.merge(kindValue, 1, Integer::sum);
        }

        // 8. Compute edge breakdown
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
                elapsed,
                allNodes
        );
    }

    /**
     * Execute the indexing pipeline with batched streaming to H2.
     * <p>
     * Unlike {@link #run}, this method does NOT hold all nodes/edges in memory.
     * It processes files in batches and flushes each batch to H2, then releases
     * the batch memory. No linkers, layer classification, or Neo4j are used.
     *
     * @param repoPath     root of the repository to analyze
     * @param parallelism  max parallel threads, or null for adaptive (virtual threads)
     * @param batchSize    number of files per H2 flush batch
     * @param incremental  if true, use file content hashing to skip unchanged files
     * @param onProgress   optional callback for progress reporting (may be null)
     * @return the analysis result containing graph data and statistics
     */
    public AnalysisResult runBatchedIndex(Path repoPath, Integer parallelism, int batchSize,
                                          boolean incremental, Consumer<String> onProgress) {
        Instant start = Instant.now();
        Consumer<String> report = onProgress != null ? onProgress : msg -> {};

        final Path root = repoPath.toAbsolutePath().normalize();

        // Always use H2 cache as the primary store during indexing
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        AnalysisCache cache;
        try {
            cache = new AnalysisCache(cachePath);
        } catch (Exception e) {
            log.error("Failed to open H2 store at {}", cachePath, e);
            return new AnalysisResult(0, 0, 0, 0,
                    Map.of(), Map.of(), Map.of(), Map.of(), Duration.ZERO);
        }

        try {
            return runBatchedWithCache(root, parallelism, batchSize, incremental, cache, report, start);
        } finally {
            cache.close();
        }
    }

    private AnalysisResult runBatchedWithCache(Path root, Integer parallelism, int batchSize,
                                                boolean incremental, AnalysisCache cache,
                                                Consumer<String> report, Instant start) {
        // 0. Load project config for pipeline filtering
        ProjectConfig projectConfig = ProjectConfigLoader.loadProjectConfig(root);
        DetectorRegistry effectiveRegistry = registry;

        if (projectConfig.hasDetectorCategoryFilter()) {
            effectiveRegistry = effectiveRegistry.filterByCategories(
                    projectConfig.getDetectorCategories());
            report.accept("Detector categories: " + projectConfig.getDetectorCategories());
        }
        if (projectConfig.hasDetectorIncludeFilter()) {
            effectiveRegistry = effectiveRegistry.filterByNames(
                    projectConfig.getDetectorInclude());
            report.accept("Detector include: " + projectConfig.getDetectorInclude());
        }
        if (parallelism == null && projectConfig.getPipelineParallelism() != null) {
            parallelism = projectConfig.getPipelineParallelism();
            report.accept("Pipeline parallelism: " + parallelism + " (from config)");
        }

        // 1. Discover files
        report.accept("Discovering files...");
        List<DiscoveredFile> files = fileDiscovery.discover(root);

        if (projectConfig.hasLanguageFilter()) {
            Set<String> allowedLanguages = new HashSet<>(projectConfig.getLanguages());
            files = files.stream()
                    .filter(f -> allowedLanguages.contains(f.language()))
                    .toList();
            report.accept("Language filter active: " + projectConfig.getLanguages());
        }
        if (projectConfig.hasExcludePatterns()) {
            List<String> excludes = projectConfig.getExclude();
            List<java.util.regex.Pattern> compiledExcludes = compileExcludePatterns(excludes);
            files = files.stream()
                    .filter(f -> !matchesAnyCompiledExclude(f.path().toString(), compiledExcludes))
                    .toList();
            report.accept("Exclude patterns: " + excludes);
        }

        int totalFiles = files.size();
        report.accept("Found " + totalFiles + " files");

        // Compute language breakdown
        Map<String, Integer> languageBreakdown = new HashMap<>();
        for (DiscoveredFile f : files) {
            languageBreakdown.merge(f.language(), 1, Integer::sum);
        }

        // 2. Process files in batches
        report.accept("Indexing " + totalFiles + " files in batches of " + batchSize + "...");

        final DetectorRegistry detectorRegistry = effectiveRegistry;
        int totalNodesWritten = 0;
        int totalEdgesWritten = 0;
        int filesAnalyzed = 0;
        int cacheHits = 0;
        int batchNumber = 0;
        Map<String, Integer> nodeBreakdown = new HashMap<>();
        Map<String, Integer> edgeBreakdown = new HashMap<>();
        Map<String, Integer> frameworkBreakdown = new HashMap<>();

        // Clear previous index data if not incremental
        if (!incremental) {
            cache.clear();
        }

        var batchExecutorService = parallelism != null && parallelism > 0
                ? Executors.newFixedThreadPool(parallelism)
                : Executors.newVirtualThreadPerTaskExecutor();
        List<DiscoveredFile> batch = new ArrayList<>(batchSize);
        for (int fileIdx = 0; fileIdx < files.size(); fileIdx++) {
            batch.add(files.get(fileIdx));

            if (batch.size() >= batchSize || fileIdx == files.size() - 1) {
                batchNumber++;
                report.accept("Processing batch " + batchNumber + " (" + batch.size() + " files)...");

                // Analyze batch in parallel
                DetectorResult[] resultSlots = new DetectorResult[batch.size()];
                int[] batchCacheHits = {0};

                {
                    List<Future<?>> futures = new ArrayList<>(batch.size());
                    for (int i = 0; i < batch.size(); i++) {
                        final int idx = i;
                        final DiscoveredFile file = batch.get(idx);
                        futures.add(batchExecutorService.submit(() -> {
                            if (incremental) {
                                try {
                                    Path absPath = root.resolve(file.path());
                                    String hash = FileHasher.hash(absPath);
                                    if (cache.isCached(hash)) {
                                        var cached = cache.loadCachedResults(hash);
                                        if (cached != null) {
                                            resultSlots[idx] = DetectorResult.of(cached.nodes(), cached.edges());
                                            synchronized (batchCacheHits) {
                                                batchCacheHits[0]++;
                                            }
                                            return null;
                                        }
                                    }
                                    DetectorResult result = analyzeFile(file, root, detectorRegistry);
                                    resultSlots[idx] = result;
                                    if (result != null && (!result.nodes().isEmpty() || !result.edges().isEmpty())) {
                                        cache.storeResults(hash, file.path().toString(), file.language(),
                                                result.nodes(), result.edges());
                                    }
                                } catch (IOException e) {
                                    log.debug("Could not hash file {}", file.path(), e);
                                    resultSlots[idx] = analyzeFile(file, root, detectorRegistry);
                                }
                            } else {
                                resultSlots[idx] = analyzeFile(file, root, detectorRegistry);
                            }
                            return null;
                        }));
                    }

                    // Collect in order
                    for (int i = 0; i < futures.size(); i++) {
                        try {
                            futures.get(i).get();
                        } catch (ExecutionException e) {
                            log.warn("Analysis failed for {}", batch.get(i).path(), e.getCause());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("Analysis interrupted for {}", batch.get(i).path());
                        }
                    }
                }

                cacheHits += batchCacheHits[0];

                // Collect batch results and flush non-cached to H2
                List<CodeNode> batchNodes = new ArrayList<>();
                List<CodeEdge> batchEdges = new ArrayList<>();
                int batchFilesAnalyzed = 0;

                for (int i = 0; i < resultSlots.length; i++) {
                    DetectorResult result = resultSlots[i];
                    if (result != null && (!result.nodes().isEmpty() || !result.edges().isEmpty())) {
                        batchFilesAnalyzed++;
                        // Only store non-incremental results (incremental already stored above)
                        if (!incremental) {
                            batchNodes.addAll(result.nodes());
                            batchEdges.addAll(result.edges());
                        }
                        // Tag nodes with service name if configured (multi-repo mode)
                        String svcName = config.getServiceName();
                        if (svcName != null && !svcName.isBlank()) {
                            for (CodeNode node : result.nodes()) {
                                node.getProperties().put("service", svcName);
                            }
                        }
                        // Track breakdowns
                        for (CodeNode node : result.nodes()) {
                            nodeBreakdown.merge(node.getKind().getValue(), 1, Integer::sum);
                            Object fw = node.getProperties().get("framework");
                            if (fw != null && !fw.toString().isEmpty()) {
                                frameworkBreakdown.merge(fw.toString(), 1, Integer::sum);
                            }
                        }
                        for (var edge : result.edges()) {
                            edgeBreakdown.merge(edge.getKind().getValue(), 1, Integer::sum);
                        }
                        totalNodesWritten += result.nodes().size();
                        totalEdgesWritten += result.edges().size();
                    }
                }

                filesAnalyzed += batchFilesAnalyzed;

                // For non-incremental mode, batch-flush to H2
                if (!incremental && (!batchNodes.isEmpty() || !batchEdges.isEmpty())) {
                    String batchId = "batch:" + batchNumber + ":" + System.nanoTime();
                    cache.storeBatchResults(batchId, "batch-" + batchNumber,
                            "mixed", batchNodes, batchEdges);
                }

                // Release batch memory
                batch.clear();
            }
        }
        batchExecutorService.close();

        if (cacheHits > 0) {
            report.accept("Cache hits: " + cacheHits + " / " + totalFiles + " files");
        }

        // Record run
        String commitSha = getGitHead(root);
        cache.recordRun(commitSha, filesAnalyzed);

        Duration elapsed = Duration.between(start, Instant.now());
        report.accept("Index complete - " + totalNodesWritten + " nodes, "
                + totalEdgesWritten + " edges written to H2");

        return new AnalysisResult(
                totalFiles,
                filesAnalyzed,
                totalNodesWritten,
                totalEdgesWritten,
                languageBreakdown,
                nodeBreakdown,
                edgeBreakdown,
                frameworkBreakdown,
                elapsed
        );
    }

    // =========================================================================
    // Smart Index Pipeline (config-first, module-partitioned)
    // =========================================================================

    /**
     * Config-first, module-partitioned indexing pipeline.
     * <p>
     * Phase 1 scans config files to build an {@link InfrastructureRegistry}.
     * Phase 2 discovers source files, partitions them by module, pre-filters
     * each module with {@link ArchitectureKeywordFilter}, then runs detectors
     * in parallel and flushes results to H2 per batch.
     * <p>
     * This method is additive — existing {@link #runBatchedIndex} is unchanged.
     *
     * @param repoPath     root of the repository to analyze
     * @param parallelism  max parallel threads, or null for virtual threads
     * @param batchSize    files per H2 flush batch
     * @param incremental  if true, use file content hashing to skip unchanged files
     * @param onProgress   optional progress callback
     * @return analysis result with phase timing in the report
     */
    public AnalysisResult runSmartIndex(Path repoPath, Integer parallelism, int batchSize,
                                        boolean incremental, Consumer<String> onProgress) {
        Instant start = Instant.now();
        Consumer<String> report = onProgress != null ? onProgress : msg -> {};
        final Path root = repoPath.toAbsolutePath().normalize();

        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        AnalysisCache cache;
        try {
            cache = new AnalysisCache(cachePath);
        } catch (Exception e) {
            log.error("Failed to open H2 store at {}", cachePath, e);
            return new AnalysisResult(0, 0, 0, 0,
                    Map.of(), Map.of(), Map.of(), Map.of(), Duration.ZERO);
        }

        try {
            return runSmartWithCache(root, parallelism, batchSize, incremental, cache, report, start);
        } finally {
            cache.close();
        }
    }

    private AnalysisResult runSmartWithCache(Path root, Integer parallelism, int batchSize,
                                              boolean incremental, AnalysisCache cache,
                                              Consumer<String> report, Instant start) {
        // ── Phase 1: Config scanning ──────────────────────────────────────────
        Instant phase1Start = Instant.now();
        report.accept("Phase 1: Scanning config files...");
        InfrastructureRegistry infraRegistry = configScanner.scan(root);
        long phase1Ms = Duration.between(phase1Start, Instant.now()).toMillis();
        report.accept("Phase 1 complete - " + infraRegistry.size()
                + " infrastructure endpoint(s) in " + phase1Ms + "ms");
        if (infraRegistry.getServiceName() != null) {
            report.accept("Service: " + infraRegistry.getServiceName());
            // Propagate to config if not already set
            if (config.getServiceName() == null || config.getServiceName().isBlank()) {
                config.setServiceName(infraRegistry.getServiceName());
            }
        }

        // ── Phase 2: File discovery + project config ───────────────────────────
        Instant phase2Start = Instant.now();
        report.accept("Phase 2: Discovering files...");

        ProjectConfig projectConfig = ProjectConfigLoader.loadProjectConfig(root);
        DetectorRegistry effectiveRegistry = registry;

        if (projectConfig.hasDetectorCategoryFilter()) {
            effectiveRegistry = effectiveRegistry.filterByCategories(
                    projectConfig.getDetectorCategories());
        }
        if (projectConfig.hasDetectorIncludeFilter()) {
            effectiveRegistry = effectiveRegistry.filterByNames(
                    projectConfig.getDetectorInclude());
        }
        if (parallelism == null && projectConfig.getPipelineParallelism() != null) {
            parallelism = projectConfig.getPipelineParallelism();
        }

        List<DiscoveredFile> allFiles = fileDiscovery.discover(root);

        if (projectConfig.hasLanguageFilter()) {
            Set<String> allowed = new HashSet<>(projectConfig.getLanguages());
            allFiles = allFiles.stream().filter(f -> allowed.contains(f.language())).toList();
        }
        if (projectConfig.hasExcludePatterns()) {
            List<java.util.regex.Pattern> compiledExcludes =
                    compileExcludePatterns(projectConfig.getExclude());
            allFiles = allFiles.stream()
                    .filter(f -> !matchesAnyCompiledExclude(f.path().toString(), compiledExcludes))
                    .toList();
        }

        int totalFiles = allFiles.size();

        // Compute language breakdown
        Map<String, Integer> languageBreakdown = new HashMap<>();
        for (DiscoveredFile f : allFiles) {
            languageBreakdown.merge(f.language(), 1, Integer::sum);
        }

        // ── Phase 3: Module partitioning ──────────────────────────────────────
        Map<String, List<DiscoveredFile>> modules = detectModules(root, allFiles);
        report.accept("Phase 2 complete - " + totalFiles + " files in "
                + modules.size() + " module(s) in "
                + Duration.between(phase2Start, Instant.now()).toMillis() + "ms");

        // ── Phase 4: Per-module analysis with keyword pre-filter ──────────────
        if (!incremental) {
            cache.clear();
        }

        final DetectorRegistry detectorRegistry = effectiveRegistry;
        int totalNodesWritten = 0;
        int totalEdgesWritten = 0;
        int filesAnalyzed = 0;
        int filesSkipped = 0;
        int cacheHits = 0;
        int batchNumber = 0;
        Map<String, Integer> nodeBreakdown = new HashMap<>();
        Map<String, Integer> edgeBreakdown = new HashMap<>();
        Map<String, Integer> frameworkBreakdown = new HashMap<>();

        var executorService = parallelism != null && parallelism > 0
                ? Executors.newFixedThreadPool(parallelism)
                : Executors.newVirtualThreadPerTaskExecutor();

        // Process modules in sorted order for determinism
        List<String> sortedModuleKeys = new ArrayList<>(modules.keySet());
        sortedModuleKeys.sort(String::compareTo);

        try (var executor = executorService) {
            List<DiscoveredFile> pendingBatch = new ArrayList<>(batchSize);
            int moduleIndex = 0;

            for (String moduleKey : sortedModuleKeys) {
                List<DiscoveredFile> moduleFiles = modules.get(moduleKey);
                moduleIndex++;
                report.accept("Processing module " + moduleIndex + "/" + sortedModuleKeys.size()
                        + ": " + moduleKey + " (" + moduleFiles.size() + " files)");

                // Pre-filter source files with keyword filter; always pass structured files
                List<DiscoveredFile> filtered = new ArrayList<>(moduleFiles.size());
                for (DiscoveredFile file : moduleFiles) {
                    if (STRUCTURED_LANGUAGES.contains(file.language())) {
                        // Always include config/structured files
                        filtered.add(file);
                    } else {
                        // Read and check for architecture keywords
                        try {
                            Path absPath = root.resolve(file.path());
                            byte[] raw = Files.readAllBytes(absPath);
                            if (keywordFilter.shouldAnalyze(raw, file.language())) {
                                filtered.add(file);
                            } else {
                                filesSkipped++;
                            }
                        } catch (IOException e) {
                            log.debug("Could not read for keyword filter {}", file.path(), e);
                            filtered.add(file); // include on error
                        }
                    }
                }

                // Add filtered files to pending batch
                for (DiscoveredFile file : filtered) {
                    pendingBatch.add(file);
                    if (pendingBatch.size() >= batchSize) {
                        batchNumber++;
                        var batchResult = processSmartBatch(pendingBatch, root, executor,
                                detectorRegistry, infraRegistry, incremental, cache,
                                nodeBreakdown, edgeBreakdown, frameworkBreakdown,
                                batchNumber, report);
                        totalNodesWritten += batchResult[0];
                        totalEdgesWritten += batchResult[1];
                        filesAnalyzed += batchResult[2];
                        cacheHits += batchResult[3];
                        pendingBatch.clear();
                    }
                }
            }

            // Flush remaining files
            if (!pendingBatch.isEmpty()) {
                batchNumber++;
                var batchResult = processSmartBatch(pendingBatch, root, executor,
                        detectorRegistry, infraRegistry, incremental, cache,
                        nodeBreakdown, edgeBreakdown, frameworkBreakdown,
                        batchNumber, report);
                totalNodesWritten += batchResult[0];
                totalEdgesWritten += batchResult[1];
                filesAnalyzed += batchResult[2];
                cacheHits += batchResult[3];
                pendingBatch.clear();
            }
        }

        if (filesSkipped > 0) {
            report.accept("Keyword filter: skipped " + filesSkipped + " / " + totalFiles
                    + " files (" + (filesSkipped * 100 / Math.max(1, totalFiles)) + "%)");
        }
        if (cacheHits > 0) {
            report.accept("Cache hits: " + cacheHits + " / " + totalFiles + " files");
        }

        String commitSha = getGitHead(root);
        cache.recordRun(commitSha, filesAnalyzed);

        Duration elapsed = Duration.between(start, Instant.now());
        report.accept("Smart index complete - " + totalNodesWritten + " nodes, "
                + totalEdgesWritten + " edges written to H2");

        return new AnalysisResult(
                totalFiles,
                filesAnalyzed,
                totalNodesWritten,
                totalEdgesWritten,
                languageBreakdown,
                nodeBreakdown,
                edgeBreakdown,
                frameworkBreakdown,
                elapsed
        );
    }

    /** Analyze one batch, flush to H2, return [nodes, edges, filesAnalyzed, cacheHits]. */
    private int[] processSmartBatch(
            List<DiscoveredFile> batch, Path root,
            java.util.concurrent.ExecutorService executor,
            DetectorRegistry detectorRegistry, InfrastructureRegistry infraRegistry,
            boolean incremental, AnalysisCache cache,
            Map<String, Integer> nodeBreakdown, Map<String, Integer> edgeBreakdown,
            Map<String, Integer> frameworkBreakdown,
            int batchNumber, Consumer<String> report) {

        report.accept("Processing batch " + batchNumber + " (" + batch.size() + " files)...");

        DetectorResult[] slots = new DetectorResult[batch.size()];
        int[] batchCacheHits = {0};

        List<Future<?>> futures = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            final int idx = i;
            final DiscoveredFile file = batch.get(idx);
            futures.add(executor.submit(() -> {
                if (incremental) {
                    try {
                        Path absPath = root.resolve(file.path());
                        String hash = FileHasher.hash(absPath);
                        if (cache.isCached(hash)) {
                            var cached = cache.loadCachedResults(hash);
                            if (cached != null) {
                                slots[idx] = DetectorResult.of(cached.nodes(), cached.edges());
                                synchronized (batchCacheHits) { batchCacheHits[0]++; }
                                return null;
                            }
                        }
                        DetectorResult result = analyzeFileWithRegistry(file, root, detectorRegistry, infraRegistry);
                        slots[idx] = result;
                        if (result != null && (!result.nodes().isEmpty() || !result.edges().isEmpty())) {
                            cache.storeResults(hash, file.path().toString(), file.language(),
                                    result.nodes(), result.edges());
                        }
                    } catch (IOException e) {
                        log.debug("Could not hash {}", file.path(), e);
                        slots[idx] = analyzeFileWithRegistry(file, root, detectorRegistry, infraRegistry);
                    }
                } else {
                    slots[idx] = analyzeFileWithRegistry(file, root, detectorRegistry, infraRegistry);
                }
                return null;
            }));
        }

        for (int i = 0; i < futures.size(); i++) {
            try {
                futures.get(i).get();
            } catch (ExecutionException e) {
                log.warn("Analysis failed for {}", batch.get(i).path(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Analysis interrupted for {}", batch.get(i).path());
            }
        }

        int nodes = 0, edges = 0, analyzed = 0;
        List<CodeNode> batchNodes = new ArrayList<>();
        List<CodeEdge> batchEdges = new ArrayList<>();

        for (DetectorResult result : slots) {
            if (result != null && (!result.nodes().isEmpty() || !result.edges().isEmpty())) {
                analyzed++;
                if (!incremental) {
                    batchNodes.addAll(result.nodes());
                    batchEdges.addAll(result.edges());
                }
                String svcName = config.getServiceName();
                if (svcName != null && !svcName.isBlank()) {
                    for (CodeNode node : result.nodes()) {
                        node.getProperties().put("service", svcName);
                    }
                }
                for (CodeNode node : result.nodes()) {
                    nodeBreakdown.merge(node.getKind().getValue(), 1, Integer::sum);
                    Object fw = node.getProperties().get("framework");
                    if (fw != null && !fw.toString().isEmpty()) {
                        frameworkBreakdown.merge(fw.toString(), 1, Integer::sum);
                    }
                }
                for (var edge : result.edges()) {
                    edgeBreakdown.merge(edge.getKind().getValue(), 1, Integer::sum);
                }
                nodes += result.nodes().size();
                edges += result.edges().size();
            }
        }

        if (!incremental && (!batchNodes.isEmpty() || !batchEdges.isEmpty())) {
            String batchId = "batch:" + batchNumber + ":" + System.nanoTime();
            cache.storeBatchResults(batchId, "batch-" + batchNumber, "mixed", batchNodes, batchEdges);
        }

        return new int[]{nodes, edges, analyzed, batchCacheHits[0]};
    }

    /**
     * Partition discovered files into modules based on build-file boundary markers.
     * <p>
     * Files are assigned to the deepest module directory that contains them.
     * Files with no matching module are placed in a {@code "root"} partition.
     * The returned map is a {@link TreeMap} for deterministic iteration.
     *
     * @param root  absolute repository root (used only for logging)
     * @param files all discovered files (paths relative to root)
     * @return module name → file list, sorted by module name
     */
    Map<String, List<DiscoveredFile>> detectModules(Path root, List<DiscoveredFile> files) {
        // Collect unique module directories from boundary marker files
        Set<String> moduleDirs = new java.util.TreeSet<>();
        for (DiscoveredFile file : files) {
            if (MODULE_BOUNDARY_MARKERS.contains(file.path().getFileName().toString())) {
                Path parent = file.path().getParent();
                moduleDirs.add(parent != null ? parent.toString().replace('\\', '/') : "");
            }
        }

        // If no module boundaries found, treat everything as root
        if (moduleDirs.isEmpty()) {
            Map<String, List<DiscoveredFile>> single = new TreeMap<>();
            single.put("root", new ArrayList<>(files));
            return single;
        }

        Map<String, List<DiscoveredFile>> result = new TreeMap<>();

        for (DiscoveredFile file : files) {
            String fileStr = file.path().toString().replace('\\', '/');
            String bestModule = null;
            int bestDepth = -1;

            for (String moduleDir : moduleDirs) {
                boolean matches = moduleDir.isEmpty()
                        || fileStr.startsWith(moduleDir + "/")
                        || fileStr.equals(moduleDir);
                if (matches) {
                    int depth = moduleDir.isEmpty() ? 0 : moduleDir.split("/").length;
                    if (depth > bestDepth) {
                        bestDepth = depth;
                        bestModule = moduleDir;
                    }
                }
            }

            String key = bestModule != null ? bestModule : "root";
            result.computeIfAbsent(key, k -> new ArrayList<>()).add(file);
        }

        log.debug("detectModules: {} module(s) detected in {}", result.size(), root);
        return result;
    }

    /**
     * Analyze a single file using the given registries.
     * Identical to {@link #analyzeFile(DiscoveredFile, Path, DetectorRegistry)} but
     * also passes the {@link InfrastructureRegistry} into each {@link DetectorContext}.
     */
    DetectorResult analyzeFileWithRegistry(DiscoveredFile file, Path repoPath,
                                            DetectorRegistry detectorRegistry,
                                            InfrastructureRegistry infraRegistry) {
        Instant fileStart = Instant.now();
        Path absPath = repoPath.resolve(file.path());

        String content;
        try {
            byte[] raw = Files.readAllBytes(absPath);
            content = DetectorUtils.decodeContent(raw);
        } catch (IOException e) {
            log.debug("Could not read file: {}", absPath, e);
            return DetectorResult.empty();
        }

        if (isMinified(file, content)) {
            log.debug("Skipping detectors for minified file: {}", file.path());
            String moduleName = DetectorUtils.deriveModuleName(file.path().toString(), file.language());
            CodeNode node = new CodeNode(
                    "file:" + file.path() + ":module:" + (moduleName != null ? moduleName : file.path().getFileName().toString()),
                    NodeKind.MODULE,
                    file.path().getFileName().toString());
            node.setFilePath(file.path().toString());
            node.setModule(moduleName);
            node.setProperties(new java.util.LinkedHashMap<>(Map.of("minified", true)));
            return DetectorResult.of(List.of(node), List.of());
        }

        Object parsedData = null;
        if (STRUCTURED_LANGUAGES.contains(file.language())) {
            parsedData = parser.parse(file.language(), content, file.path().toString());
        }

        String moduleName = DetectorUtils.deriveModuleName(file.path().toString(), file.language());

        var ctx = new DetectorContext(
                file.path().toString(),
                file.language(),
                content,
                parsedData,
                moduleName,
                infraRegistry
        );

        List<Detector> detectors = detectorRegistry.detectorsForLanguage(file.language());
        if (detectors.isEmpty()) {
            return DetectorResult.empty();
        }

        var allNodes = new ArrayList<CodeNode>();
        var allEdges = new ArrayList<io.github.randomcodespace.iq.model.CodeEdge>();

        for (Detector detector : detectors) {
            try {
                DetectorResult result = detector.detect(ctx);
                allNodes.addAll(result.nodes());
                allEdges.addAll(result.edges());
            } catch (Throwable e) {
                log.debug("Detector {} failed on {}: {}",
                        detector.getName(), file.path(), e.getMessage());
            }
        }

        AntlrParserFactory.clearCache();

        long fileMs = Duration.between(fileStart, Instant.now()).toMillis();
        if (fileMs > 500) {
            log.debug("Slow file {} ({}): {}ms", file.path(), file.language(), fileMs);
        }

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
     * Check whether a file is minified (e.g. *.min.js, *.bundle.js) and large
     * enough that running detectors would be wasteful.
     * <p>
     * Heuristic: filename ends with .min.js or .bundle.js, file is &gt; 10 KB,
     * and average line length exceeds 500 characters.
     */
    private boolean isMinified(DiscoveredFile file, String content) {
        String name = file.path().getFileName().toString();
        if (!(name.endsWith(".min.js") || name.endsWith(".bundle.js")
                || name.endsWith(".min.css") || name.endsWith(".min.mjs"))) {
            return false;
        }
        if (file.sizeBytes() <= 10_240) {
            return false;
        }
        // Average line length check
        String[] lines = content.split("\n", -1);
        if (lines.length == 0) return false;
        long totalChars = 0;
        for (String line : lines) {
            totalChars += line.length();
        }
        return (totalChars / lines.length) > 500;
    }

    /**
     * Analyze a single file using the default registry.
     */
    DetectorResult analyzeFile(DiscoveredFile file, Path repoPath) {
        return analyzeFile(file, repoPath, registry);
    }

    /**
     * Analyze a single file using the given (possibly filtered) registry.
     */
    DetectorResult analyzeFile(DiscoveredFile file, Path repoPath, DetectorRegistry detectorRegistry) {
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

        // Minified file detection: create a node with minified=true but skip detectors
        if (isMinified(file, content)) {
            log.debug("Skipping detectors for minified file: {}", file.path());
            String moduleName = DetectorUtils.deriveModuleName(file.path().toString(), file.language());
            CodeNode node = new CodeNode(
                    "file:" + file.path() + ":module:" + (moduleName != null ? moduleName : file.path().getFileName().toString()),
                    NodeKind.MODULE,
                    file.path().getFileName().toString());
            node.setFilePath(file.path().toString());
            node.setModule(moduleName);
            node.setProperties(new java.util.LinkedHashMap<>(Map.of("minified", true)));
            return DetectorResult.of(List.of(node), List.of());
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
        List<Detector> detectors = detectorRegistry.detectorsForLanguage(file.language());
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
            } catch (Throwable e) {
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

    /**
     * Pre-compile exclude glob patterns into regex Pattern objects.
     */
    private static List<java.util.regex.Pattern> compileExcludePatterns(List<String> excludePatterns) {
        if (excludePatterns == null) return List.of();
        return excludePatterns.stream()
                .map(p -> compileGlob(p.replace('\\', '/')))
                .toList();
    }

    /**
     * Check whether a file path matches any of the given pre-compiled exclude patterns.
     */
    private static boolean matchesAnyExclude(String filePath, List<String> excludePatterns) {
        if (excludePatterns == null) return false;
        List<java.util.regex.Pattern> compiled = compileExcludePatterns(excludePatterns);
        return matchesAnyCompiledExclude(filePath, compiled);
    }

    /**
     * Check whether a file path matches any of the given pre-compiled patterns.
     */
    private static boolean matchesAnyCompiledExclude(String filePath, List<java.util.regex.Pattern> compiledPatterns) {
        if (compiledPatterns == null || compiledPatterns.isEmpty()) return false;
        String normalized = filePath.replace('\\', '/');
        for (java.util.regex.Pattern pattern : compiledPatterns) {
            if (pattern.matcher(normalized).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Compile a glob pattern into a regex Pattern.
     * '*' matches any non-separator sequence, '**' matches everything (including separators).
     * All regex special characters are properly escaped.
     */
    private static java.util.regex.Pattern compileGlob(String pattern) {
        StringBuilder regex = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '*') {
                if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '*') {
                    regex.append(".*");
                    i += 2;
                    // skip trailing /
                    if (i < pattern.length() && pattern.charAt(i) == '/') {
                        i++;
                    }
                } else {
                    regex.append("[^/]*");
                    i++;
                }
            } else if (c == '?') {
                regex.append("[^/]");
                i++;
            } else if (".+^${}()|[]\\".indexOf(c) >= 0) {
                // S5: Properly escape all regex special characters
                regex.append('\\').append(c);
                i++;
            } else {
                regex.append(c);
                i++;
            }
        }
        regex.append("$");
        return java.util.regex.Pattern.compile(regex.toString());
    }
}
