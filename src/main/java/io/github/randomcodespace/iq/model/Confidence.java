package io.github.randomcodespace.iq.model;

import java.util.Objects;

/**
 * Confidence in the truth of a node or edge, based on the parser pipeline that
 * produced it.
 * <p>
 * Lower values mean the assertion comes from textual patterns; higher values
 * mean the assertion is backed by parsed structure or resolved symbol types.
 * Comparable: {@code LEXICAL} &lt; {@code SYNTACTIC} &lt; {@code RESOLVED}.
 * <p>
 * Numeric mapping (via {@link #score()}) is stable and intended for Cypher /
 * MCP / SPA filtering. The enum itself is the authoritative form; the score
 * exists only as a convenience for clients that want a single number.
 *
 * @see <a href="../../../docs/specs/2026-04-27-resolver-spi-and-java-pilot-design.md">Sub-project 1 design — §5.3 Confidence schema</a>
 */
public enum Confidence {

    /** Pattern-only match (regex). The detector saw a textual pattern. */
    LEXICAL(0.6),

    /** AST or parse tree match, no symbol resolution. The detector saw structure. */
    SYNTACTIC(0.8),

    /** Resolved via a {@code SymbolResolver} — the detector saw resolved types. */
    RESOLVED(0.95);

    private final double score;

    Confidence(double score) {
        this.score = score;
    }

    /**
     * Stable numeric score for filtering / threshold logic.
     * Mapping: {@code LEXICAL=0.6}, {@code SYNTACTIC=0.8}, {@code RESOLVED=0.95}.
     */
    public double score() {
        return score;
    }

    /**
     * Look up a {@code Confidence} by case-insensitive name.
     *
     * @throws NullPointerException     if {@code value} is null
     * @throws IllegalArgumentException if {@code value} does not match any constant
     */
    public static Confidence fromString(String value) {
        Objects.requireNonNull(value, "Confidence value must not be null");
        for (Confidence c : values()) {
            if (c.name().equalsIgnoreCase(value)) {
                return c;
            }
        }
        throw new IllegalArgumentException("Unknown Confidence: " + value);
    }
}
