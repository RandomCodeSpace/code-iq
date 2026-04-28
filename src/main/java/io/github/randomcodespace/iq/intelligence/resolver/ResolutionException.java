package io.github.randomcodespace.iq.intelligence.resolver;

import java.nio.file.Path;

/**
 * Thrown by a {@link SymbolResolver} when bootstrap or per-file resolution
 * fails in a way the resolver cannot recover from. Carries enough context
 * (file path + language) for the orchestrator to log a useful message before
 * falling back to syntactic detection.
 *
 * <p>Checked exception by design — symbol resolution is a long-tail of file-
 * specific failures (corrupted source, dependency cycles, classpath holes),
 * and the orchestrator must explicitly decide whether to skip the file or
 * abort the whole pass. Swallowing silently is not an option.
 */
public class ResolutionException extends Exception {

    private final Path file;
    private final String language;

    /**
     * @param message human-readable description of the failure
     * @param cause   underlying exception (may be null)
     * @param file    the file (or project root for bootstrap failures) that
     *                couldn't be resolved
     * @param language the language identifier for the resolver involved
     */
    public ResolutionException(String message, Throwable cause, Path file, String language) {
        super(message, cause);
        this.file = file;
        this.language = language;
    }

    /** Convenience constructor without an underlying cause. */
    public ResolutionException(String message, Path file, String language) {
        this(message, null, file, language);
    }

    /** @return the file (or project root) that couldn't be resolved. May be {@code null}. */
    public Path file() {
        return file;
    }

    /** @return the language identifier (e.g. {@code "java"}). May be {@code null}. */
    public String language() {
        return language;
    }
}
