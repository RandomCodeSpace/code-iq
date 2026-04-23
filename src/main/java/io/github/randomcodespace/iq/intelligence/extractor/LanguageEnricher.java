package io.github.randomcodespace.iq.intelligence.extractor;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Runs all {@link LanguageExtractor} beans after {@link io.github.randomcodespace.iq.intelligence.lexical.LexicalEnricher}
 * during the {@code enrich} command.
 *
 * <p>Builds a combined node registry (by id and fqn), groups nodes by source file,
 * reads each file once, and dispatches to matching extractors. Results (edges, type hints)
 * are written back into the in-memory node/edge lists before Neo4j bulk-load.
 *
 * <p>Extraction failures log a warning and are skipped — the pipeline never aborts.
 */
@Component
public class LanguageEnricher {
    private static final String PROP_JAVASCRIPT = "javascript";
    private static final String PROP_TYPESCRIPT = "typescript";


    private static final Logger log = LoggerFactory.getLogger(LanguageEnricher.class);

    /**
     * Language alias map: normalises file-extension languages to extractor language keys.
     * e.g. PROP_JAVASCRIPT nodes are handled by the PROP_TYPESCRIPT extractor.
     */
    private static final Map<String, String> LANGUAGE_ALIASES = Map.of(
            PROP_JAVASCRIPT, PROP_TYPESCRIPT
    );

    private final List<LanguageExtractor> extractors;

    public LanguageEnricher(List<LanguageExtractor> extractors) {
        this.extractors = List.copyOf(extractors);
    }

    /**
     * Enrich nodes with language-specific intelligence and add new edges.
     *
     * @param nodes    All enriched nodes (post-linker, post-classifier, post-lexical).
     * @param edges    Mutable edge list — new edges are appended in place.
     * @param rootPath Absolute root path of the analysed repository (for file reads).
     */
    public void enrich(List<CodeNode> nodes, List<CodeEdge> edges, Path rootPath) {
        if (extractors.isEmpty()) {
            log.debug("No LanguageExtractor beans registered — skipping language enrichment");
            return;
        }

        // Build combined node registry: id → node, fqn → node
        Map<String, CodeNode> nodeRegistry = buildRegistry(nodes);

        // Build extractor lookup: normalised language → extractor
        Map<String, LanguageExtractor> extractorByLanguage = new HashMap<>();
        for (LanguageExtractor extractor : extractors) {
            extractorByLanguage.put(extractor.getLanguage(), extractor);
        }

        // Group nodes by file path (read each file only once).
        // TreeMap guarantees deterministic iteration order (alphabetical by path).
        Map<String, List<CodeNode>> nodesByFile = new TreeMap<>();
        for (CodeNode node : nodes) {
            if (node.getFilePath() != null) {
                nodesByFile.computeIfAbsent(node.getFilePath(), k -> new ArrayList<>()).add(node);
            }
        }

        // Collect files that have a matching extractor.
        // Skip non-source files (test, generated, minified, binary, text) — they don't need enrichment.
        record FileTask(String filePath, List<CodeNode> fileNodes, LanguageExtractor extractor, String language) {}
        List<FileTask> tasks = new ArrayList<>();
        for (Map.Entry<String, List<CodeNode>> entry : nodesByFile.entrySet()) {
            String filePath = entry.getKey();

            // Skip non-source files based on node properties
            List<CodeNode> fileNodes = entry.getValue();
            if (!fileNodes.isEmpty()) {
                Object fileType = fileNodes.get(0).getProperties().get("file_type");
                if (fileType != null) {
                    String ft = fileType.toString();
                    if ("test".equals(ft) || "generated".equals(ft) || "minified".equals(ft)
                            || "binary".equals(ft) || "text".equals(ft) || "filtered".equals(ft)) {
                        continue;
                    }
                }
            }

            String language = detectLanguage(filePath);
            if (language == null) continue;
            String resolvedLanguage = LANGUAGE_ALIASES.getOrDefault(language, language);
            LanguageExtractor extractor = extractorByLanguage.get(resolvedLanguage);
            if (extractor == null) continue;
            tasks.add(new FileTask(filePath, fileNodes, extractor, language));
        }

        if (tasks.isEmpty()) {
            log.info("Language enrichment: no files matched any extractor");
            return;
        }

        // Process files with per-file timeout.
        // Uses virtual threads for parallelism on large codebases.
        var newEdges = java.util.Collections.synchronizedList(new ArrayList<CodeEdge>());
        var edgesAdded = new java.util.concurrent.atomic.AtomicInteger(0);
        var typeHintsAdded = new java.util.concurrent.atomic.AtomicInteger(0);

        // try-with-resources: ExecutorService#close() handles shutdown + awaitTermination.
        // Each future.get() below has its own 5-minute timeout + cancel, so by the time
        // close() runs, all tasks are terminal and close() returns promptly.
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(tasks.size());
            for (FileTask task : tasks) {
                futures.add(executor.submit(() -> {
                    if (Thread.interrupted()) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    String content = readFile(rootPath, task.filePath());
                    if (content == null) return null;

                    // Skip minified files — they hang parsers and contain no useful structure
                    if (isLikelyMinified(task.filePath(), content)) {
                        log.debug("Skipping minified file for enrichment: {}", task.filePath());
                        return null;
                    }

                    DetectorContext ctx = new DetectorContext(
                            task.filePath(), task.language(), content, nodeRegistry, null);

                    for (CodeNode node : task.fileNodes()) {
                        if (Thread.interrupted()) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        try {
                            LanguageExtractionResult result = task.extractor().extract(ctx, node);
                            newEdges.addAll(result.callEdges());
                            newEdges.addAll(result.symbolReferences());
                            edgesAdded.addAndGet(result.callEdges().size() + result.symbolReferences().size());
                            for (Map.Entry<String, String> hint : result.typeHints().entrySet()) {
                                node.getProperties().put(hint.getKey(), hint.getValue());
                                typeHintsAdded.incrementAndGet();
                            }
                        } catch (Exception e) {
                            log.warn("LanguageExtractor {} failed on node {} in {}: {}",
                                    task.extractor().getClass().getSimpleName(),
                                    node.getId(), task.filePath(), e.getMessage());
                        }
                    }
                    return null;
                }));
            }

            // Collect results — no timeout-based skipping (zero data loss).
            // Files that are slow will complete naturally; ANTLR is already bypassed
            // for TS/JS and size-guarded for other languages at the factory level.
            for (int i = 0; i < futures.size(); i++) {
                try {
                    futures.get(i).get(5, TimeUnit.MINUTES);
                } catch (java.util.concurrent.TimeoutException e) {
                    futures.get(i).cancel(true);
                    log.warn("⏱️ Language enrichment timed out for {} (5min safety limit)", tasks.get(i).filePath());
                } catch (java.util.concurrent.ExecutionException e) {
                    log.warn("Language enrichment failed for {}: {}", tasks.get(i).filePath(), e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        edges.addAll(newEdges);
        log.info("Language enrichment: {} edges added, {} type hints added across {} extractors ({} files)",
                edgesAdded.get(), typeHintsAdded.get(), extractorByLanguage.size(), tasks.size());
    }

    private Map<String, CodeNode> buildRegistry(List<CodeNode> nodes) {
        Map<String, CodeNode> registry = new HashMap<>();
        for (CodeNode node : nodes) {
            if (node.getId() != null) {
                registry.put(node.getId(), node);
            }
            if (node.getFqn() != null && !node.getFqn().isEmpty()) {
                registry.put(node.getFqn(), node);
            }
        }
        return registry;
    }

    private String readFile(Path rootPath, String filePath) {
        try {
            Path resolved = rootPath.resolve(filePath);
            if (!Files.exists(resolved)) return null;
            return Files.readString(resolved, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("Could not read file {}: {}", filePath, e.getMessage());
            return null;
        }
    }

    /**
     * Check if a file is likely minified (long lines, large size) to skip enrichment.
     */
    private static boolean isLikelyMinified(String filePath, String content) {
        if (content.length() < 50_000) return false;
        String name = filePath.contains("/") ? filePath.substring(filePath.lastIndexOf('/') + 1) : filePath;
        boolean jsOrCss = name.endsWith(".js") || name.endsWith(".mjs") || name.endsWith(".cjs")
                || name.endsWith(".css") || name.endsWith(".jsx") || name.endsWith(".ts");
        if (!jsOrCss && !name.endsWith(".min.js") && !name.endsWith(".bundle.js")) return false;
        int newlines = 0;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') newlines++;
        }
        if (newlines == 0) newlines = 1;
        return content.length() / newlines > 1000;
    }

    /**
     * Map file extension to language string (mirrors FileDiscovery conventions).
     */
    static String detectLanguage(String filePath) {
        if (filePath == null) return null;
        int dot = filePath.lastIndexOf('.');
        if (dot < 0) return null;
        return switch (filePath.substring(dot + 1).toLowerCase()) {
            case "java" -> "java";
            case "ts", "tsx" -> PROP_TYPESCRIPT;
            case "js", "jsx", "mjs", "cjs" -> PROP_JAVASCRIPT;
            case "py", "pyw" -> "python";
            case "go" -> "go";
            default -> null;
        };
    }
}
