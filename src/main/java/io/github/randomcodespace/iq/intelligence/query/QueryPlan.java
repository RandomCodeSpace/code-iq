package io.github.randomcodespace.iq.intelligence.query;

import io.github.randomcodespace.iq.intelligence.CapabilityLevel;

import java.util.Map;

/**
 * Immutable result produced by the {@link QueryPlanner}.
 * Describes how a given query intent should be executed for a specific language.
 *
 * @param queryType        The type of query being planned.
 * @param language         Normalised lowercase language name (e.g. {@code "java"}, {@code "python"}).
 * @param route            The selected retrieval path.
 * @param capabilities     Snapshot of the capability levels for dimensions relevant to this query.
 * @param degradationNote  Human-readable explanation when {@code route} is
 *                         {@link QueryRoute#LEXICAL_FIRST} or {@link QueryRoute#DEGRADED};
 *                         {@code null} for {@link QueryRoute#GRAPH_FIRST} and {@link QueryRoute#MERGED}.
 */
public record QueryPlan(
        QueryType queryType,
        String language,
        QueryRoute route,
        Map<CapabilityDimension, CapabilityLevel> capabilities,
        String degradationNote
) {
    /**
     * Convenience factory for a fully-capable plan (no degradation note).
     */
    public static QueryPlan of(QueryType queryType, String language, QueryRoute route,
                               Map<CapabilityDimension, CapabilityLevel> capabilities) {
        return new QueryPlan(queryType, language, route, capabilities, null);
    }

    /** Returns {@code true} if this plan involves any graph traversal. */
    public boolean usesGraph() {
        return route == QueryRoute.GRAPH_FIRST || route == QueryRoute.MERGED;
    }

    /** Returns {@code true} if this plan involves lexical/text search. */
    public boolean usesLexical() {
        return route == QueryRoute.LEXICAL_FIRST || route == QueryRoute.MERGED;
    }
}
