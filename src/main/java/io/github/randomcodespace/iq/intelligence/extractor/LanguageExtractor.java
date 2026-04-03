package io.github.randomcodespace.iq.intelligence.extractor;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.model.CodeNode;

/**
 * Strategy interface for language-specific enrichment extractors.
 *
 * <p>Implementations are stateless Spring {@code @Component} beans auto-discovered
 * via classpath scan. Each extractor targets a single language and deepens the
 * capability matrix beyond what the general intelligence layer provides.
 *
 * <p>Extractors run during {@code enrich} (after {@link io.github.randomcodespace.iq.intelligence.lexical.LexicalEnricher})
 * and must never run during {@code index}.
 */
public interface LanguageExtractor {

    /**
     * The primary language this extractor targets (e.g. "java", "typescript", "python", "go").
     * Matches the language values produced by {@code FileDiscovery}.
     */
    String getLanguage();

    /**
     * Extract additional intelligence for the given node from its source file.
     *
     * <p>The {@code ctx} carries file content and a node registry via {@code parsedData}
     * (cast to {@code Map<String, CodeNode>} — a combined id + fqn index built by
     * {@link LanguageEnricher}).
     *
     * @param ctx  Detector context for the node's source file; {@code parsedData} contains
     *             the node registry as {@code Map<String, CodeNode>}.
     * @param node The specific node to enrich.
     * @return Extraction result; never {@code null}.
     */
    LanguageExtractionResult extract(DetectorContext ctx, CodeNode node);
}
