package io.github.randomcodespace.iq.analyzer;

import java.nio.charset.StandardCharsets;
import io.github.randomcodespace.iq.analyzer.linker.Linker;
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.cache.FileHasher;
import io.github.randomcodespace.iq.cli.VersionCommand;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.config.ProjectConfig;
import io.github.randomcodespace.iq.config.ProjectConfigLoader;
import io.github.randomcodespace.iq.detector.AbstractAntlrDetector;
import io.github.randomcodespace.iq.detector.Detector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorRegistry;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorUtils;
import io.github.randomcodespace.iq.grammar.AntlrParserFactory;
import io.github.randomcodespace.iq.intelligence.FileClassification;
import io.github.randomcodespace.iq.intelligence.FileEntry;
import io.github.randomcodespace.iq.intelligence.FileInventory;
import io.github.randomcodespace.iq.intelligence.RepositoryIdentity;
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
    private static final String PROP_FRAMEWORK = "framework";
    private static final String PROP_ROOT = "root";
    private static final String PROP_SERVICE = "service";


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

        // 1b. Resolve repository identity and build file inventory
        RepositoryIdentity repoIdentity = RepositoryIdentity.resolve(root);
        FileInventory fileInventory = buildFileInventory(files, cache);

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
        try (var executor = createExecutor(parallelism)) {
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
                                FileClassifier.FileType ft = FileClassifier.classify(file.path(), file.language());
                                String snippet = computeSnippetFromFile(root.resolve(file.path()), ft);
                                cacheRef.storeResults(hash, file.path().toString(), file.language(),
                                        result.nodes(), result.edges(), "DETECTED", "antlr",
                                        ft.name().toLowerCase(), snippet);
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
                    futures.get(i).get(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    futures.get(i).cancel(true);
                    DiscoveredFile timedOutFile = files.get(i);
                    log.warn("⏱️ ANTLR timed out for {} (30s), running regex fallback", timedOutFile.path());
                    DetectorResult regexResult = analyzeFileRegexOnly(timedOutFile, root, detectorRegistry);
                    resultSlots[i] = regexResult;
                    // Store regex fallback result to cache with explicit detection_method
                    if (cache != null && regexResult != null
                            && (!regexResult.nodes().isEmpty() || !regexResult.edges().isEmpty())) {
                        try {
                            Path absPath = root.resolve(timedOutFile.path());
                            String hash = FileHasher.hash(absPath);
                            cache.storeResults(hash, timedOutFile.path().toString(),
                                    timedOutFile.language(), regexResult.nodes(), regexResult.edges(),
                                    "DETECTED", "regex_fallback");
                        } catch (IOException ioe) {
                            log.debug("Could not hash for regex fallback cache: {}", timedOutFile.path(), ioe);
                        }
                    }
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
        var builder = new GraphBuilder(repoIdentity, VersionCommand.VERSION);
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
        String projectDirName = java.util.Objects.toString(root.getFileName(), PROP_ROOT);
        var serviceResult = serviceDetector.detect(allNodes, builder.getEdges(), projectDirName, root);
        if (!serviceResult.serviceNodes().isEmpty()) {
            serviceResult.serviceNodes().forEach(n -> n.setProvenance(builder.getProvenance()));
            builder.addNodes(serviceResult.serviceNodes());
            builder.addEdges(serviceResult.serviceEdges());
            allNodes = builder.getNodes(); // refresh reference after adding service nodes
        }

        // 5c. Tag nodes with service name if configured (multi-repo mode) -- overrides auto-detected
        String serviceName = config.getServiceName();
        if (serviceName != null && !serviceName.isBlank()) {
            for (CodeNode node : allNodes) {
                node.getProperties().put(PROP_SERVICE, serviceName);
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
            Object fw = node.getProperties().get(PROP_FRAMEWORK);
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

        try (var batchExecutor = createExecutor(parallelism)) {
        List<DiscoveredFile> batch = new ArrayList<>(batchSize);
        for (int fileIdx = 0; fileIdx < files.size(); fileIdx++) {
            batch.add(files.get(fileIdx));

            if (batch.size() >= batchSize || fileIdx == files.size() - 1) {
                batchNumber++;
                report.accept("Processing batch " + batchNumber + " (" + batch.size() + " files)...");
                Instant batchStart = Instant.now();

                // Analyze batch in parallel
                DetectorResult[] resultSlots = new DetectorResult[batch.size()];
                var batchCacheHits = new java.util.concurrent.atomic.AtomicInteger(0);

                {
                    List<Future<?>> futures = new ArrayList<>(batch.size());
                    for (int i = 0; i < batch.size(); i++) {
                        final int idx = i;
                        final DiscoveredFile file = batch.get(idx);
                        futures.add(batchExecutor.submit(() -> {
                            if (incremental) {
                                try {
                                    Path absPath = root.resolve(file.path());
                                    String hash = FileHasher.hash(absPath);
                                    if (cache.isCached(hash)) {
                                        var cached = cache.loadCachedResults(hash);
                                        if (cached != null) {
                                            resultSlots[idx] = DetectorResult.of(cached.nodes(), cached.edges());
                                            batchCacheHits.incrementAndGet();
                                            return null;
                                        }
                                    }
                                    DetectorResult result = analyzeFile(file, root, detectorRegistry);
                                    resultSlots[idx] = result;
                                    if (result != null && (!result.nodes().isEmpty() || !result.edges().isEmpty())) {
                                        FileClassifier.FileType ft = FileClassifier.classify(file.path(), file.language());
                                        String snippet = computeSnippetFromFile(root.resolve(file.path()), ft);
                                        cache.storeResults(hash, file.path().toString(), file.language(),
                                                result.nodes(), result.edges(), "DETECTED", "antlr",
                                                ft.name().toLowerCase(), snippet);
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
                            futures.get(i).get(30, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (java.util.concurrent.TimeoutException e) {
                            futures.get(i).cancel(true);
                            // Zero data loss: run regex-only fallback instead of skipping
                            DiscoveredFile timedOutFile = batch.get(i);
                            log.warn("⏱️ ANTLR timed out for {} (30s), running regex fallback", timedOutFile.path());
                            DetectorResult regexResult = analyzeFileRegexOnly(timedOutFile, root, detectorRegistry);
                            resultSlots[i] = regexResult;
                            // Store regex fallback result to cache with explicit detection_method
                            if (incremental && regexResult != null
                                    && (!regexResult.nodes().isEmpty() || !regexResult.edges().isEmpty())) {
                                try {
                                    Path absPath = root.resolve(timedOutFile.path());
                                    String hash = FileHasher.hash(absPath);
                                    cache.storeResults(hash, timedOutFile.path().toString(),
                                            timedOutFile.language(), regexResult.nodes(), regexResult.edges(),
                                            "DETECTED", "regex_fallback");
                                } catch (IOException ioe) {
                                    log.debug("Could not hash for regex fallback cache: {}", timedOutFile.path(), ioe);
                                }
                            }
                        } catch (ExecutionException e) {
                            log.warn("Analysis failed for {}", batch.get(i).path(), e.getCause());
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            log.warn("Analysis interrupted for {}", batch.get(i).path());
                        }
                        int done = i + 1;
                        if (done % 100 == 0 || done == futures.size()) {
                            report.accept("  " + done + "/" + futures.size() + " files...");
                        }
                    }
                }

                long batchMs = Duration.between(batchStart, Instant.now()).toMillis();
                long avgMs = batch.isEmpty() ? 0 : batchMs / batch.size();
                report.accept("  Batch " + batchNumber + " done: " + batchMs + "ms (" + avgMs + "ms/file avg)");

                cacheHits += batchCacheHits.get();

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
                                node.getProperties().put(PROP_SERVICE, svcName);
                            }
                        }
                        // Track breakdowns
                        for (CodeNode node : result.nodes()) {
                            nodeBreakdown.merge(node.getKind().getValue(), 1, Integer::sum);
                            Object fw = node.getProperties().get(PROP_FRAMEWORK);
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
        }

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

        // Process modules in sorted order for determinism
        List<String> sortedModuleKeys = new ArrayList<>(modules.keySet());
        sortedModuleKeys.sort(String::compareTo);

        try (var executor = createExecutor(parallelism)) {
            List<DiscoveredFile> pendingBatch = new ArrayList<>(batchSize);
            List<CodeNode> filteredNodes = new ArrayList<>();
            Map<String, String> contentCache = new HashMap<>();
            int moduleIndex = 0;

            for (String moduleKey : sortedModuleKeys) {
                List<DiscoveredFile> moduleFiles = modules.get(moduleKey);
                moduleIndex++;
                report.accept("Processing module " + moduleIndex + "/" + sortedModuleKeys.size()
                        + ": " + moduleKey + " (" + moduleFiles.size() + " files)");

                // Pre-filter source files with keyword filter; always pass structured files.
                // Cache decoded content from the keyword filter to avoid re-reading in analyzeFile.
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
                                // Cache decoded content to avoid duplicate read in analyzeFile
                                contentCache.put(file.path().toString(),
                                        DetectorUtils.decodeContent(raw));
                            } else {
                                filesSkipped++;
                                // Zero data loss: create minimal inventory node for filtered files
                                String filteredFileName = java.util.Objects.toString(file.path().getFileName(), "");
                                CodeNode fileNode = new CodeNode(
                                    "file:" + file.path() + ":module:" + filteredFileName,
                                    NodeKind.MODULE,
                                    filteredFileName);
                                fileNode.setFilePath(file.path().toString());
                                fileNode.setModule(DetectorUtils.deriveModuleName(file.path().toString(), file.language()));
                                fileNode.getProperties().put("status", "filtered");
                                fileNode.getProperties().put("language", file.language());
                                fileNode.getProperties().put("detection_method", "none");
                                filteredNodes.add(fileNode);
                                log.debug("⏭️ SKIP: {} ({}, {} bytes) — no architecture keywords",
                                        file.path(), file.language(), raw.length);
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
                        var batchResult = processSmartBatch(pendingBatch, root, executor.delegate(),
                                detectorRegistry, infraRegistry, incremental, cache,
                                nodeBreakdown, edgeBreakdown, frameworkBreakdown,
                                batchNumber, report, contentCache, filteredNodes);
                        totalNodesWritten += batchResult[0];
                        totalEdgesWritten += batchResult[1];
                        filesAnalyzed += batchResult[2];
                        cacheHits += batchResult[3];
                        pendingBatch.clear();
                        filteredNodes.clear();
                    }
                }
            }

            // Flush remaining files (including any accumulated filtered nodes)
            if (!pendingBatch.isEmpty() || !filteredNodes.isEmpty()) {
                batchNumber++;
                var batchResult = processSmartBatch(pendingBatch, root, executor.delegate(),
                        detectorRegistry, infraRegistry, incremental, cache,
                        nodeBreakdown, edgeBreakdown, frameworkBreakdown,
                        batchNumber, report, contentCache, filteredNodes);
                totalNodesWritten += batchResult[0];
                totalEdgesWritten += batchResult[1];
                filesAnalyzed += batchResult[2];
                cacheHits += batchResult[3];
                pendingBatch.clear();
                filteredNodes.clear();
            }
            // Clear content cache after all batches in this module to free memory
            contentCache.clear();
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
            int batchNumber, Consumer<String> report,
            Map<String, String> contentCache,
            List<CodeNode> filteredNodes) {

        report.accept("Processing batch " + batchNumber + " (" + batch.size() + " files)...");
        Instant batchStart = Instant.now();

        DetectorResult[] slots = new DetectorResult[batch.size()];
        var batchCacheHits = new java.util.concurrent.atomic.AtomicInteger(0);

        List<Future<?>> futures = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            final int idx = i;
            final DiscoveredFile file = batch.get(idx);
            final String cachedContent = contentCache.remove(file.path().toString());
            futures.add(executor.submit(() -> {
                if (incremental) {
                    try {
                        Path absPath = root.resolve(file.path());
                        String hash = FileHasher.hash(absPath);
                        if (cache.isCached(hash)) {
                            var cached = cache.loadCachedResults(hash);
                            if (cached != null) {
                                slots[idx] = DetectorResult.of(cached.nodes(), cached.edges());
                                batchCacheHits.incrementAndGet();
                                return null;
                            }
                        }
                        DetectorResult result = analyzeFileWithRegistry(file, root, detectorRegistry, infraRegistry, cachedContent);
                        slots[idx] = result;
                        if (result != null && (!result.nodes().isEmpty() || !result.edges().isEmpty())) {
                            FileClassifier.FileType ft = FileClassifier.classify(file.path(), file.language());
                            String snippet = cachedContent != null
                                    ? computeSnippet(cachedContent, ft)
                                    : computeSnippetFromFile(root.resolve(file.path()), ft);
                            cache.storeResults(hash, file.path().toString(), file.language(),
                                    result.nodes(), result.edges(), "DETECTED", "antlr",
                                    ft.name().toLowerCase(), snippet);
                        }
                    } catch (IOException e) {
                        log.debug("Could not hash {}", file.path(), e);
                        slots[idx] = analyzeFileWithRegistry(file, root, detectorRegistry, infraRegistry, cachedContent);
                    }
                } else {
                    slots[idx] = analyzeFileWithRegistry(file, root, detectorRegistry, infraRegistry, cachedContent);
                }
                return null;
            }));
        }

        for (int i = 0; i < futures.size(); i++) {
            try {
                futures.get(i).get(30, java.util.concurrent.TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException e) {
                futures.get(i).cancel(true);
                DiscoveredFile timedOutFile = batch.get(i);
                log.warn("⏱️ ANTLR timed out for {} (30s), running regex fallback", timedOutFile.path());
                DetectorResult regexResult = analyzeFileRegexOnly(timedOutFile, root, detectorRegistry);
                slots[i] = regexResult;
                // Store regex fallback result to cache with explicit detection_method
                if (incremental && regexResult != null
                        && (!regexResult.nodes().isEmpty() || !regexResult.edges().isEmpty())) {
                    try {
                        Path absPath = root.resolve(timedOutFile.path());
                        String hash = FileHasher.hash(absPath);
                        cache.storeResults(hash, timedOutFile.path().toString(),
                                timedOutFile.language(), regexResult.nodes(), regexResult.edges(),
                                "DETECTED", "regex_fallback");
                    } catch (IOException ioe) {
                        log.debug("Could not hash for regex fallback cache: {}", timedOutFile.path(), ioe);
                    }
                }
            } catch (ExecutionException e) {
                log.warn("Analysis failed for {}", batch.get(i).path(), e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Analysis interrupted for {}", batch.get(i).path());
            }
            int done = i + 1;
            if (done % 100 == 0 || done == futures.size()) {
                report.accept("  " + done + "/" + futures.size() + " files...");
            }
        }

        long batchMs = Duration.between(batchStart, Instant.now()).toMillis();
        long avgMs = batch.isEmpty() ? 0 : batchMs / batch.size();
        report.accept("  Batch " + batchNumber + " done: " + batchMs + "ms (" + avgMs + "ms/file avg)");

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
                        node.getProperties().put(PROP_SERVICE, svcName);
                    }
                }
                for (CodeNode node : result.nodes()) {
                    nodeBreakdown.merge(node.getKind().getValue(), 1, Integer::sum);
                    Object fw = node.getProperties().get(PROP_FRAMEWORK);
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

        // Add filtered file inventory nodes to batch results
        if (filteredNodes != null && !filteredNodes.isEmpty()) {
            batchNodes.addAll(filteredNodes);
            for (CodeNode fn : filteredNodes) {
                nodeBreakdown.merge(fn.getKind().getValue(), 1, Integer::sum);
            }
            nodes += filteredNodes.size();
        }

        if (!incremental && (!batchNodes.isEmpty() || !batchEdges.isEmpty())) {
            String batchId = "batch:" + batchNumber + ":" + System.nanoTime();
            cache.storeBatchResults(batchId, "batch-" + batchNumber, "mixed", batchNodes, batchEdges);
        }

        return new int[]{nodes, edges, analyzed, batchCacheHits.get()};
    }

    /**
     * Partition discovered files into modules based on build-file boundary markers.
     * <p>
     * Files are assigned to the deepest module directory that contains them.
     * Files with no matching module are placed in a {@code PROP_ROOT} partition.
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
            if (MODULE_BOUNDARY_MARKERS.contains(java.util.Objects.toString(file.path().getFileName(), ""))) {
                Path parent = file.path().getParent();
                moduleDirs.add(parent != null ? parent.toString().replace('\\', '/') : "");
            }
        }

        // If no module boundaries found, treat everything as root
        if (moduleDirs.isEmpty()) {
            Map<String, List<DiscoveredFile>> single = new TreeMap<>();
            single.put(PROP_ROOT, new ArrayList<>(files));
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

            String key = bestModule != null ? bestModule : PROP_ROOT;
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
        return analyzeFileWithRegistry(file, repoPath, detectorRegistry, infraRegistry, null);
    }

    /**
     * Analyze a single file using the given registries, optionally with pre-read content.
     * When {@code preReadContent} is non-null, it is used directly instead of reading from disk,
     * avoiding a duplicate file read (the content was already read during keyword filtering).
     *
     * @param preReadContent decoded file content from the keyword filter, or null to read from disk
     */
    DetectorResult analyzeFileWithRegistry(DiscoveredFile file, Path repoPath,
                                            DetectorRegistry detectorRegistry,
                                            InfrastructureRegistry infraRegistry,
                                            String preReadContent) {
        Instant fileStart = Instant.now();

        // Classify file type before reading content
        FileClassifier.FileType fileType = FileClassifier.classify(file.path(), file.language());

        // Binary files: inventory-only node, no content read needed
        if (fileType == FileClassifier.FileType.BINARY) {
            return createInventoryNode(file, "binary");
        }

        // Generated files: inventory-only node, skip detectors
        if (fileType == FileClassifier.FileType.GENERATED) {
            return createInventoryNode(file, "generated");
        }

        String content;
        if (preReadContent != null) {
            content = preReadContent;
        } else {
            Path absPath = repoPath.resolve(file.path());
            try {
                byte[] raw = Files.readAllBytes(absPath);
                content = DetectorUtils.decodeContent(raw);
            } catch (IOException e) {
                log.debug("Could not read file: {}", absPath, e);
                return DetectorResult.empty();
            }
        }

        // Test files: inventory-only node with file_type=test
        if (fileType == FileClassifier.FileType.TEST) {
            return createInventoryNode(file, "test");
        }

        // Text files (unknown language): inventory-only node
        if (fileType == FileClassifier.FileType.TEXT) {
            return createInventoryNode(file, "text");
        }

        if (isMinified(file, content)) {
            log.debug("Skipping detectors for minified file: {}", file.path());
            String moduleName = DetectorUtils.deriveModuleName(file.path().toString(), file.language());
            String fileNameStr = java.util.Objects.toString(file.path().getFileName(), "");
            CodeNode node = new CodeNode(
                    "file:" + file.path() + ":module:" + (moduleName != null ? moduleName : fileNameStr),
                    NodeKind.MODULE,
                    fileNameStr);
            node.setFilePath(file.path().toString());
            node.setModule(moduleName);
            node.setProperties(new java.util.LinkedHashMap<>(Map.of("minified", true, "file_type", "minified")));
            return DetectorResult.of(List.of(node), List.of());
        }

        // SOURCE and CONFIG files: run detectors
        String fileTypeStr = (fileType == FileClassifier.FileType.CONFIG) ? "config" : "source";

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
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                break;
            }
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
        if (fileMs > 5000) {
            log.warn("🐢 SLOW: {} took {}ms", file.path(), fileMs);
        } else if (fileMs > 500) {
            log.info("🐢 SLOW: {} took {}ms", file.path(), fileMs);
        }

        // Set module and file_type on all nodes
        for (CodeNode node : allNodes) {
            if (moduleName != null && (node.getModule() == null || node.getModule().isEmpty())) {
                node.setModule(moduleName);
            }
            ensureMutableProperties(node).put("file_type", fileTypeStr);
        }

        return DetectorResult.of(allNodes, allEdges);
    }

    /**
     * Check whether a file is minified and large enough that running detectors
     * would be wasteful or cause ANTLR/regex hangs.
     * <p>
     * Two-tier heuristic:
     * <ol>
     *   <li>Filename match (*.min.js, *.bundle.js, etc.) + size &gt; 10 KB + avg line &gt; 500 chars</li>
     *   <li>Content-based: any JS/CSS/MJS file &gt; 50 KB with avg line &gt; 1000 chars (catches
     *       minified files without .min suffix, e.g. webpack output named app.js or vendor.js)</li>
     * </ol>
     */
    /**
     * Wrapper around ExecutorService that implements AutoCloseable with a bounded
     * shutdown — prevents the default close() from hanging up to 24 hours on stuck
     * ANTLR threads.
     */
    private record BoundedExecutor(java.util.concurrent.ExecutorService delegate) implements AutoCloseable {
        <T> Future<T> submit(java.util.concurrent.Callable<T> task) { return delegate.submit(task); }

        @Override
        public void close() {
            delegate.shutdown();
            try {
                if (!delegate.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    delegate.shutdownNow();
                    if (!delegate.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        log.warn("Executor did not terminate cleanly; stuck ANTLR threads will be reclaimed at JVM exit");
                    }
                }
            } catch (InterruptedException e) {
                delegate.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private BoundedExecutor createExecutor(Integer parallelism) {
        var exec = parallelism != null && parallelism > 0
                ? Executors.newFixedThreadPool(parallelism, Thread.ofPlatform().daemon(true).factory())
                : Executors.newVirtualThreadPerTaskExecutor();
        return new BoundedExecutor(exec);
    }

    private boolean isMinified(DiscoveredFile file, String content) {
        String name = java.util.Objects.toString(file.path().getFileName(), "");
        boolean nameHint = name.endsWith(".min.js") || name.endsWith(".bundle.js")
                || name.endsWith(".min.css") || name.endsWith(".min.mjs");
        boolean jsOrCss = name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs")
                || name.endsWith(".css") || name.endsWith(".jsx") || name.endsWith(".ts");

        // Small files are never treated as minified
        if (file.sizeBytes() <= 10_240) {
            return false;
        }

        // Average line length check
        int newlines = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') newlines++;
        }
        if (newlines == 0) newlines = 1;
        long avgLineLen = content.length() / newlines;

        // Tier 1: known minified suffixes with relaxed threshold
        if (nameHint && avgLineLen > 500) {
            return true;
        }

        // Tier 2: any JS/CSS file > 50 KB with very long lines (minified without .min suffix)
        if (jsOrCss && file.sizeBytes() > 50_000 && avgLineLen > 1000) {
            return true;
        }

        return false;
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

        // Classify file type before reading content
        FileClassifier.FileType fileType = FileClassifier.classify(file.path(), file.language());

        // Binary files: inventory-only node, no content read needed
        if (fileType == FileClassifier.FileType.BINARY) {
            return createInventoryNode(file, "binary");
        }

        // Generated files: inventory-only node, skip detectors
        if (fileType == FileClassifier.FileType.GENERATED) {
            return createInventoryNode(file, "generated");
        }

        // Read file content
        String content;
        try {
            byte[] raw = Files.readAllBytes(absPath);
            content = DetectorUtils.decodeContent(raw);
        } catch (IOException e) {
            log.debug("Could not read file: {}", absPath, e);
            return DetectorResult.empty();
        }

        // Test files: inventory-only node with file_type=test
        if (fileType == FileClassifier.FileType.TEST) {
            return createInventoryNode(file, "test");
        }

        // Text files (unknown language): inventory-only node
        if (fileType == FileClassifier.FileType.TEXT) {
            return createInventoryNode(file, "text");
        }

        // Minified file detection: create a node with minified=true but skip detectors
        if (isMinified(file, content)) {
            log.debug("Skipping detectors for minified file: {}", file.path());
            String moduleName = DetectorUtils.deriveModuleName(file.path().toString(), file.language());
            String fileNameStr = java.util.Objects.toString(file.path().getFileName(), "");
            CodeNode node = new CodeNode(
                    "file:" + file.path() + ":module:" + (moduleName != null ? moduleName : fileNameStr),
                    NodeKind.MODULE,
                    fileNameStr);
            node.setFilePath(file.path().toString());
            node.setModule(moduleName);
            node.setProperties(new java.util.LinkedHashMap<>(Map.of("minified", true, "file_type", "minified")));
            return DetectorResult.of(List.of(node), List.of());
        }

        // SOURCE and CONFIG files: run detectors
        String fileTypeStr = (fileType == FileClassifier.FileType.CONFIG) ? "config" : "source";

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
                if (detMs > 2000) {
                    log.warn("🐢 SLOW DETECTOR: {} on {}: {}ms",
                            detector.getName(), file.path(), detMs);
                } else if (detMs > 100) {
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
        if (fileMs > 5000) {
            log.warn("🐢 SLOW: {} took {}ms", file.path(), fileMs);
        } else if (fileMs > 500) {
            log.info("🐢 SLOW: {} took {}ms", file.path(), fileMs);
        }

        // Set module and file_type on all nodes
        for (CodeNode node : allNodes) {
            if (moduleName != null && (node.getModule() == null || node.getModule().isEmpty())) {
                node.setModule(moduleName);
            }
            ensureMutableProperties(node).put("file_type", fileTypeStr);
        }

        return DetectorResult.of(allNodes, allEdges);
    }

    /**
     * Create an inventory-only node for files that should not have detectors run.
     */
    private static DetectorResult createInventoryNode(DiscoveredFile file, String fileType) {
        String moduleName = DetectorUtils.deriveModuleName(file.path().toString(), file.language());
        String fileNameStr = java.util.Objects.toString(file.path().getFileName(), "");
        CodeNode node = new CodeNode(
                "file:" + file.path() + ":module:" + (moduleName != null ? moduleName : fileNameStr),
                NodeKind.MODULE,
                fileNameStr);
        node.setFilePath(file.path().toString());
        node.setModule(moduleName);
        node.setProperties(new java.util.LinkedHashMap<>(Map.of(
                "file_type", fileType,
                "language", file.language() != null ? file.language() : "")));
        return DetectorResult.of(List.of(node), List.of());
    }

    /**
     * Regex-only analysis fallback for files where ANTLR timed out.
     * Ensures zero data loss — every file produces nodes via regex detection.
     * Nodes are tagged with detection_method=regex_fallback.
     */
    private DetectorResult analyzeFileRegexOnly(DiscoveredFile file, Path repoPath,
            DetectorRegistry detectorRegistry) {
        Path absPath = repoPath.resolve(file.path());
        String content;
        try {
            byte[] raw = Files.readAllBytes(absPath);
            content = DetectorUtils.decodeContent(raw);
        } catch (IOException e) {
            log.debug("Could not read file for regex fallback: {}", absPath, e);
            return DetectorResult.empty();
        }

        String moduleName = DetectorUtils.deriveModuleName(file.path().toString(), file.language());
        var ctx = new DetectorContext(file.path().toString(), file.language(), content, null, moduleName);

        List<Detector> detectors = detectorRegistry.detectorsForLanguage(file.language());
        var allNodes = new ArrayList<CodeNode>();
        var allEdges = new ArrayList<io.github.randomcodespace.iq.model.CodeEdge>();

        for (Detector detector : detectors) {
            try {
                DetectorResult result;
                if (detector instanceof AbstractAntlrDetector antlrDet) {
                    result = antlrDet.detectRegexOnly(ctx);
                } else {
                    result = detector.detect(ctx);
                }
                allNodes.addAll(result.nodes());
                allEdges.addAll(result.edges());
            } catch (Throwable e) {
                log.debug("Regex fallback detector {} failed on {}: {}",
                        detector.getName(), file.path(), e.getMessage());
            }
        }

        // Tag all nodes with detection method so users know quality level
        for (CodeNode node : allNodes) {
            node.getProperties().put("detection_method", "regex_fallback");
            if (moduleName != null && (node.getModule() == null || node.getModule().isEmpty())) {
                node.setModule(moduleName);
            }
        }

        AntlrParserFactory.clearCache();
        return DetectorResult.of(allNodes, allEdges);
    }

    /**
     * Build a deterministic FileInventory from the list of discovered files.
     * Content hashes are reused from {@code cache} when available (no re-read of files).
     * Hashes remain null for files not yet present in the cache.
     */
    private static FileInventory buildFileInventory(List<DiscoveredFile> files, AnalysisCache cache) {
        List<FileEntry> entries = new ArrayList<>(files.size());
        for (DiscoveredFile f : files) {
            String relPath = f.path().toString().replace('\\', '/');
            FileClassification cls = FileEntry.classify(relPath, f.language());
            String contentHash = cache != null ? cache.getHashForPath(relPath) : null;
            entries.add(new FileEntry(relPath, f.language(), f.sizeBytes(), contentHash, cls));
        }
        return new FileInventory(entries);
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
            String sha = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int exitCode = proc.waitFor();
            if (exitCode == 0 && sha.length() >= 7) {
                return sha;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Could not determine git HEAD", e);
            return null;
        } catch (Exception e) {
            log.debug("Could not determine git HEAD", e);
        }
        return null;
    }

    /**
     * Read file content and compute snippet. Returns null on error or for binary files.
     */
    /**
     * Ensure a node's properties map is mutable. Some nodes are created with
     * immutable Map.of() which throws UnsupportedOperationException on put().
     */
    private static Map<String, Object> ensureMutableProperties(CodeNode node) {
        Map<String, Object> props = node.getProperties();
        try {
            // Test mutability — HashMap/LinkedHashMap will not throw
            props.put("_test", null);
            props.remove("_test");
            return props;
        } catch (UnsupportedOperationException e) {
            var mutable = new java.util.LinkedHashMap<>(props);
            node.setProperties(mutable);
            return mutable;
        }
    }

    private static String computeSnippetFromFile(Path absPath, FileClassifier.FileType fileType) {
        if (fileType == FileClassifier.FileType.BINARY) return null;
        try {
            byte[] raw = Files.readAllBytes(absPath);
            String content = DetectorUtils.decodeContent(raw);
            return computeSnippet(content, fileType);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Compute a snippet from file content for storage in H2.
     * Returns the first 200 lines, capped at 10KB, or null for binary files.
     */
    static String computeSnippet(String content, FileClassifier.FileType fileType) {
        if (fileType == FileClassifier.FileType.BINARY) return null;
        if (content == null || content.isEmpty()) return null;
        // First 200 lines, max 10KB
        String[] lines = content.split("\n", 201);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 200); i++) {
            if (sb.length() + lines[i].length() > 10_000) break;
            if (i > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
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
