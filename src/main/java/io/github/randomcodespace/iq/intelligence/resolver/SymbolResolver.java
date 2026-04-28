package io.github.randomcodespace.iq.intelligence.resolver;

import io.github.randomcodespace.iq.analyzer.DiscoveredFile;

import java.nio.file.Path;
import java.util.Set;

/**
 * Per-language symbol-resolution backend. The Resolver SPI mirrors the
 * {@link io.github.randomcodespace.iq.detector.Detector} SPI: each implementation
 * is a Spring {@code @Component} declaring which languages it handles, and the
 * {@link ResolverRegistry} auto-discovers them at startup.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>The orchestrator calls {@link #bootstrap(Path)} once with the project
 *       root before any per-file work. The resolver builds whatever it needs
 *       (type solvers, classpath, etc.).</li>
 *   <li>For each parsed file, the orchestrator calls
 *       {@link #resolve(DiscoveredFile, Object)} with the parsed AST. The
 *       resolver returns a language-specific {@link Resolved} carrying the
 *       resolution context, or {@link EmptyResolved#INSTANCE} if the file
 *       isn't its language.</li>
 *   <li>{@link #shutdown()} is called once at the end of the pass for cleanup
 *       (default no-op).</li>
 * </ol>
 *
 * <p>Thread safety: implementations must be safe to invoke
 * {@link #resolve(DiscoveredFile, Object)} concurrently from virtual threads
 * after a single {@link #bootstrap(Path)} call. Detector pipelines run on
 * virtual-thread pools.
 *
 * <p>Determinism: if the resolver depends on source roots or classpath, those
 * inputs must be sorted before construction so two runs over the same project
 * produce identical resolution results.
 */
public interface SymbolResolver {

    /**
     * @return language identifiers this resolver handles, lowercase, e.g.
     *         {@code Set.of("java")} or {@code Set.of("typescript",
     *         "javascript")}. Never empty, never null.
     */
    Set<String> getSupportedLanguages();

    /**
     * Build whatever language-specific resolution state is needed for a single
     * project root. Called once per analysis pass before any
     * {@link #resolve(DiscoveredFile, Object)} call.
     *
     * @param projectRoot absolute path to the project root being analyzed
     * @throws ResolutionException if bootstrap fails irrecoverably (the
     *         orchestrator will log and disable this resolver for the pass)
     */
    void bootstrap(Path projectRoot) throws ResolutionException;

    /**
     * Resolve symbols for a single parsed file.
     *
     * @param file      the file being detected
     * @param parsedAst the AST produced by the parser pipeline. Type is
     *                  language-specific (e.g. {@code CompilationUnit} for
     *                  Java, {@code ParseTree} for ANTLR languages); the
     *                  resolver checks via {@code instanceof}.
     * @return language-specific {@link Resolved} on success, or
     *         {@link EmptyResolved#INSTANCE} if this file isn't this
     *         resolver's language or {@code parsedAst} is the wrong type.
     *         Must never return {@code null}.
     * @throws ResolutionException for irrecoverable per-file failures the
     *         orchestrator should surface (rare; most failures should
     *         downgrade to {@link EmptyResolved#INSTANCE} silently).
     */
    Resolved resolve(DiscoveredFile file, Object parsedAst) throws ResolutionException;

    /** Cleanup hook. Default no-op. */
    default void shutdown() { }
}
