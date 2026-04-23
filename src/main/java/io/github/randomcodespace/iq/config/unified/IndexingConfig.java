package io.github.randomcodespace.iq.config.unified;
import java.util.List;

/**
 * Indexing-layer configuration.
 *
 * <p>{@code parallelism} is an {@link Integer}; {@code null} means "auto-detect"
 * (the Analyzer chooses {@code Runtime.availableProcessors()} or similar). Any
 * non-null value must be {@code > 0} -- enforced by {@link ConfigValidator}.
 *
 * <p>{@code parsers} is a list of parser-preference names carried by the
 * unified tree so Analyzer can filter or prefer specific parsers per run. It
 * replaces the map-of-language-to-parser form the legacy {@code ProjectConfig}
 * POJO carried; Analyzer never consumed the map at runtime, so a flat list is
 * sufficient and simpler to merge across layers.
 */
public record IndexingConfig(
        List<String> languages, List<String> include, List<String> exclude,
        Boolean incremental, String cacheDir, Integer parallelism, Integer batchSize,
        Integer maxDepth, Integer maxRadius, Integer maxFiles, Integer maxSnippetLines,
        List<String> parsers) {
    public static IndexingConfig empty() {
        return new IndexingConfig(
                List.of(), List.of(), List.of(),
                null, null, null, null,
                null, null, null, null,
                List.of());
    }
}
