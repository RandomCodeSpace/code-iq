package io.github.randomcodespace.iq.detector;

import io.github.randomcodespace.iq.analyzer.InfrastructureRegistry;
import io.github.randomcodespace.iq.intelligence.resolver.Resolved;

import java.util.Optional;

/**
 * Immutable per-file context passed to every {@link Detector#detect}.
 *
 * <p>The {@code resolved} field is the opt-in entry point for symbol-resolution
 * data. Detectors that want to upgrade emissions to {@link
 * io.github.randomcodespace.iq.model.Confidence#RESOLVED} call
 * {@code ctx.resolved().filter(Resolved::isAvailable).map(...)} before
 * downcasting to the language-specific {@code Resolved} subclass. Detectors
 * that don't care simply ignore the field — the existing pipeline works
 * unchanged when {@link #resolved()} returns {@code Optional.empty()}.
 */
public record DetectorContext(
    String filePath,
    String language,
    String content,
    Object parsedData,
    String moduleName,
    InfrastructureRegistry registry,
    Optional<Resolved> resolved
) {
    /** Compact constructor: normalize {@code null resolved} to {@link Optional#empty()}. */
    public DetectorContext {
        if (resolved == null) {
            resolved = Optional.empty();
        }
    }

    /** Minimal constructor — no parsed data, module name, registry, or resolution. */
    public DetectorContext(String filePath, String language, String content) {
        this(filePath, language, content, null, null, null, Optional.empty());
    }

    /** Backward-compat: 5-arg form without registry / resolution. */
    public DetectorContext(String filePath, String language, String content,
                           Object parsedData, String moduleName) {
        this(filePath, language, content, parsedData, moduleName, null, Optional.empty());
    }

    /** Backward-compat: 6-arg form with registry but no resolution (matches the old canonical record signature). */
    public DetectorContext(String filePath, String language, String content,
                           Object parsedData, String moduleName,
                           InfrastructureRegistry registry) {
        this(filePath, language, content, parsedData, moduleName, registry, Optional.empty());
    }

    /**
     * Return a copy of this context with the given {@link Resolved} attached.
     * Used by the orchestrator after the resolver pass to thread per-file
     * resolution into the detector. {@code null} is normalized to
     * {@link Optional#empty()}.
     */
    public DetectorContext withResolved(Resolved resolved) {
        Optional<Resolved> opt = resolved != null ? Optional.of(resolved) : Optional.empty();
        return new DetectorContext(filePath, language, content, parsedData, moduleName, registry, opt);
    }
}
