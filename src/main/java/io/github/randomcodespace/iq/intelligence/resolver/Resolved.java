package io.github.randomcodespace.iq.intelligence.resolver;

import io.github.randomcodespace.iq.model.Confidence;

/**
 * Per-file symbol resolution result.
 *
 * <p>A {@code Resolved} carries language-specific resolution state that detectors
 * can consult to upgrade their emissions from {@link Confidence#SYNTACTIC} to
 * {@link Confidence#RESOLVED}. Each language backend ships its own concrete
 * implementation (e.g. {@code JavaResolved} wraps a {@code JavaSymbolSolver}
 * plus a {@code CompilationUnit}); detectors that want resolved data downcast
 * after checking {@link #isAvailable()}.
 *
 * <p>{@link #isAvailable()} is the first gate every detector should consult.
 * If it returns {@code false}, the resolver wasn't able to resolve this file —
 * detectors must fall back to syntactic detection. The {@link EmptyResolved}
 * singleton is the canonical "not available" instance.
 */
public interface Resolved {

    /**
     * @return {@code true} if this result actually carries resolved-symbol data
     *         and detectors may safely downcast to a language-specific subtype.
     *         {@code false} for {@link EmptyResolved} or any other backend that
     *         declined to resolve this file (e.g. parse failure, unsupported
     *         language, or resolver disabled).
     */
    boolean isAvailable();

    /**
     * @return the confidence floor the orchestrator should stamp on emissions
     *         that consult this resolution. {@link Confidence#RESOLVED} for
     *         genuine resolution; {@link Confidence#LEXICAL} for
     *         {@link EmptyResolved} (i.e. nothing was actually resolved).
     */
    Confidence sourceConfidence();
}
