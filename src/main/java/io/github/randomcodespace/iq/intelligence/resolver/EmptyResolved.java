package io.github.randomcodespace.iq.intelligence.resolver;

import io.github.randomcodespace.iq.model.Confidence;

/**
 * Singleton "no resolution" {@link Resolved} — what
 * {@link io.github.randomcodespace.iq.intelligence.resolver.SymbolResolver}
 * returns when it can't resolve a file (parse failure, unsupported language,
 * resolver disabled, or no resolver registered for this file's language).
 *
 * <p>Detectors must check {@link #isAvailable()} before downcasting; they will
 * always get {@code false} from this singleton, signalling "fall back to
 * syntactic detection."
 */
public final class EmptyResolved implements Resolved {

    /** The single instance — comparable via {@code ==}. */
    public static final EmptyResolved INSTANCE = new EmptyResolved();

    private EmptyResolved() { }

    @Override
    public boolean isAvailable() {
        return false;
    }

    @Override
    public Confidence sourceConfidence() {
        // Nothing was actually resolved — emissions consulting this should NOT
        // claim RESOLVED confidence. LEXICAL is the floor; a syntactic detector
        // emitting against EmptyResolved still has its own SYNTACTIC base default.
        return Confidence.LEXICAL;
    }
}
