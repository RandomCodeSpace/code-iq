package io.github.randomcodespace.iq.intelligence;

/**
 * Confidence level for intelligence capabilities on a given language or feature.
 * Used in provenance records and capability matrix entries.
 */
public enum CapabilityLevel {
    /** Full semantic understanding — AST-level, cross-file, high confidence. */
    EXACT,
    /** Partial coverage — some constructs detected, others may be missed. */
    PARTIAL,
    /** Lexical/text search only — no structural analysis. */
    LEXICAL_ONLY,
    /** Language or feature is not supported by current extractors. */
    UNSUPPORTED
}
