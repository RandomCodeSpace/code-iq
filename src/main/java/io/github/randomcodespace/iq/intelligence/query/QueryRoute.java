package io.github.randomcodespace.iq.intelligence.query;

/**
 * The retrieval path chosen by the {@link QueryPlanner} for a given query intent and language.
 */
public enum QueryRoute {
    /**
     * Primary path: query the structural graph (Neo4j).
     * Used when capability is {@code EXACT} — AST-level analysis is available.
     */
    GRAPH_FIRST,
    /**
     * Fallback path: lexical/text search only.
     * Used when capability is {@code LEXICAL_ONLY} — no structural analysis is available.
     */
    LEXICAL_FIRST,
    /**
     * Combined path: graph results augmented with lexical search.
     * Used when capability is {@code PARTIAL} — structural analysis is incomplete
     * and lexical search fills the gaps.
     */
    MERGED,
    /**
     * Degraded path: the feature is unsupported for this language.
     * A {@code degradationNote} is included in the {@link QueryPlan} to explain what is missing.
     */
    DEGRADED
}
