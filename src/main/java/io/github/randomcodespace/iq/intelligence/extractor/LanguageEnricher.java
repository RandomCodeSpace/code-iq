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

    private static final Logger log = LoggerFactory.getLogger(LanguageEnricher.class);

    /**
     * Language alias map: normalises file-extension languages to extractor language keys.
     * e.g. "javascript" nodes are handled by the "typescript" extractor.
     */
    private static final Map<String, String> LANGUAGE_ALIASES = Map.of(
            "javascript", "typescript"
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

        int edgesAdded = 0;
        int typeHintsAdded = 0;

        for (Map.Entry<String, List<CodeNode>> entry : nodesByFile.entrySet()) {
            String filePath = entry.getKey();
            List<CodeNode> fileNodes = entry.getValue();

            String language = detectLanguage(filePath);
            if (language == null) continue;

            String resolvedLanguage = LANGUAGE_ALIASES.getOrDefault(language, language);
            LanguageExtractor extractor = extractorByLanguage.get(resolvedLanguage);
            if (extractor == null) continue;

            String content = readFile(rootPath, filePath);
            if (content == null) continue;

            DetectorContext ctx = new DetectorContext(filePath, language, content, nodeRegistry, null);

            for (CodeNode node : fileNodes) {
                try {
                    LanguageExtractionResult result = extractor.extract(ctx, node);
                    edges.addAll(result.callEdges());
                    edges.addAll(result.symbolReferences());
                    edgesAdded += result.callEdges().size() + result.symbolReferences().size();
                    for (Map.Entry<String, String> hint : result.typeHints().entrySet()) {
                        node.getProperties().put(hint.getKey(), hint.getValue());
                        typeHintsAdded++;
                    }
                } catch (Exception e) {
                    log.warn("LanguageExtractor {} failed on node {} in {}: {}",
                            extractor.getClass().getSimpleName(), node.getId(), filePath, e.getMessage());
                }
            }
        }

        log.info("Language enrichment: {} edges added, {} type hints added across {} extractors",
                edgesAdded, typeHintsAdded, extractorByLanguage.size());
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
     * Map file extension to language string (mirrors FileDiscovery conventions).
     */
    static String detectLanguage(String filePath) {
        if (filePath == null) return null;
        int dot = filePath.lastIndexOf('.');
        if (dot < 0) return null;
        return switch (filePath.substring(dot + 1).toLowerCase()) {
            case "java" -> "java";
            case "ts", "tsx" -> "typescript";
            case "js", "jsx", "mjs", "cjs" -> "javascript";
            case "py", "pyw" -> "python";
            case "go" -> "go";
            default -> null;
        };
    }
}
