package io.github.randomcodespace.iq.intelligence.query;

import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic query planner that routes a query intent to the correct retrieval path
 * based on explicit rules derived from the {@link CapabilityMatrix}.
 *
 * <p>Routing rules (no LLM, no probabilistic logic):
 * <ol>
 *   <li>{@link QueryRoute#GRAPH_FIRST} — all relevant dimensions are {@code EXACT}.</li>
 *   <li>{@link QueryRoute#MERGED} — at least one relevant dimension is {@code PARTIAL}
 *       (graph results plus lexical search for coverage).</li>
 *   <li>{@link QueryRoute#LEXICAL_FIRST} — all relevant dimensions are {@code LEXICAL_ONLY}.</li>
 *   <li>{@link QueryRoute#DEGRADED} — any relevant dimension is {@code UNSUPPORTED}.</li>
 * </ol>
 *
 * <p>{@link QueryType#SEARCH_TEXT} always routes to {@link QueryRoute#LEXICAL_FIRST}
 * regardless of language, because text search operates on raw source content, not the graph.
 */
@Service
@Profile("serving")
public class QueryPlanner {

    // ------------------------------------------------------------------
    // Query-type → relevant dimensions mapping (deterministic, static)
    // ------------------------------------------------------------------

    private static final Map<QueryType, List<CapabilityDimension>> QUERY_DIMENSIONS = Map.of(
            QueryType.FIND_SYMBOL,       List.of(CapabilityDimension.SYMBOL_DEFINITIONS),
            QueryType.FIND_REFERENCES,   List.of(CapabilityDimension.SYMBOL_REFERENCES),
            QueryType.FIND_CALLERS,      List.of(CapabilityDimension.SYMBOL_REFERENCES),
            QueryType.FIND_DEPENDENCIES, List.of(CapabilityDimension.IMPORT_RESOLUTION),
            QueryType.SEARCH_TEXT,       List.of(),   // special-cased below
            QueryType.FIND_CONFIG,       List.of(CapabilityDimension.FRAMEWORK_SEMANTICS)
    );

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Produce a {@link QueryPlan} for the given {@code queryType} and {@code language}.
     * The result is fully deterministic for the same input.
     *
     * @param queryType the type of query being planned
     * @param language  normalised lowercase language name (e.g. {@code "java"}, {@code "python"})
     * @return a non-null {@link QueryPlan}
     */
    public QueryPlan plan(QueryType queryType, String language) {
        Map<CapabilityDimension, CapabilityLevel> caps = CapabilityMatrix.forLanguage(language);

        // SEARCH_TEXT is always lexical — the graph does not index raw text content
        if (queryType == QueryType.SEARCH_TEXT) {
            return new QueryPlan(queryType, language, QueryRoute.LEXICAL_FIRST, caps, null);
        }

        List<CapabilityDimension> relevant = QUERY_DIMENSIONS.getOrDefault(queryType, List.of());

        if (relevant.isEmpty()) {
            // Unknown query type with no dimension mapping → treat as degraded
            return new QueryPlan(queryType, language, QueryRoute.DEGRADED, caps,
                    "No capability dimensions are mapped for query type " + queryType +
                    ". This query type may not be supported yet.");
        }

        Set<CapabilityLevel> levels = EnumSet.noneOf(CapabilityLevel.class);
        for (CapabilityDimension dim : relevant) {
            levels.add(caps.getOrDefault(dim, CapabilityLevel.UNSUPPORTED));
        }

        QueryRoute route = selectRoute(levels, queryType, language);
        String degradationNote = buildDegradationNote(route, levels, queryType, language, relevant);

        return new QueryPlan(queryType, language, route, caps, degradationNote);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    /**
     * Select the route given the set of capability levels for the relevant dimensions.
     * Priority: DEGRADED > LEXICAL_FIRST > MERGED > GRAPH_FIRST.
     */
    private QueryRoute selectRoute(Set<CapabilityLevel> levels,
                                   QueryType queryType, String language) {
        if (levels.contains(CapabilityLevel.UNSUPPORTED)) {
            return QueryRoute.DEGRADED;
        }
        if (levels.contains(CapabilityLevel.LEXICAL_ONLY) && levels.contains(CapabilityLevel.EXACT)) {
            // Some dimensions exact, others lexical-only → merge for best coverage
            return QueryRoute.MERGED;
        }
        if (levels.contains(CapabilityLevel.PARTIAL)) {
            return QueryRoute.MERGED;
        }
        if (levels.contains(CapabilityLevel.LEXICAL_ONLY)) {
            return QueryRoute.LEXICAL_FIRST;
        }
        // All dimensions are EXACT
        return QueryRoute.GRAPH_FIRST;
    }

    /**
     * Build a human-readable degradation note for LEXICAL_FIRST and DEGRADED routes.
     * Returns {@code null} for GRAPH_FIRST and MERGED (no explanation needed).
     */
    private String buildDegradationNote(QueryRoute route,
                                        Set<CapabilityLevel> levels,
                                        QueryType queryType,
                                        String language,
                                        List<CapabilityDimension> relevant) {
        if (route == QueryRoute.GRAPH_FIRST) return null;
        if (route == QueryRoute.MERGED)      return null;

        String lang = language == null || language.isBlank() ? "this language" : "'" + language + "'";
        String dims = relevant.stream()
                .map(d -> d.name().toLowerCase().replace('_', ' '))
                .reduce((a, b) -> a + ", " + b)
                .orElse("the requested dimensions");

        if (route == QueryRoute.DEGRADED) {
            return "Query type " + queryType + " is not supported for " + lang + ". " +
                   "The current extractor suite has no structural analysis for " + dims + ". " +
                   "Consider running the analysis on a supported language (java, typescript, " +
                   "javascript, python, go, csharp, rust) or use SEARCH_TEXT for lexical fallback.";
        }

        // LEXICAL_FIRST
        return "Query type " + queryType + " for " + lang + " uses lexical search only. " +
               "Structural graph analysis is unavailable for " + dims + " in " + lang + ". " +
               "Results may be less precise than for fully-supported languages.";
    }
}
