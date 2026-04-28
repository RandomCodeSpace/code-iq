package io.github.randomcodespace.iq.intelligence.resolver;

import io.github.randomcodespace.iq.analyzer.DiscoveredFile;
import io.github.randomcodespace.iq.model.Confidence;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract coverage for {@link SymbolResolver}. Verifies a stub implementation
 * honours the SPI invariants:
 * <ul>
 *   <li>{@link SymbolResolver#getSupportedLanguages()} returns a non-empty set</li>
 *   <li>{@link SymbolResolver#bootstrap(Path)} runs before any
 *       {@link SymbolResolver#resolve(DiscoveredFile, Object)} call</li>
 *   <li>{@link SymbolResolver#resolve(DiscoveredFile, Object)} never returns
 *       {@code null} — uses {@link EmptyResolved#INSTANCE} for the
 *       not-supported / wrong-type cases</li>
 *   <li>{@link SymbolResolver#shutdown()} default is a no-op</li>
 * </ul>
 */
class SymbolResolverContractTest {

    @Test
    void supportedLanguagesIsNonEmpty() {
        SymbolResolver r = new StubResolver(Set.of("java"));
        assertFalse(r.getSupportedLanguages().isEmpty());
        assertEquals(Set.of("java"), r.getSupportedLanguages());
    }

    @Test
    void resolveReturnsEmptyForUnknownLanguage() throws ResolutionException {
        SymbolResolver r = new StubResolver(Set.of("java"));
        r.bootstrap(Path.of("/tmp/project"));

        DiscoveredFile pyFile = new DiscoveredFile(Path.of("foo.py"), "python", 100);
        Resolved result = r.resolve(pyFile, "some-ast");

        assertSame(EmptyResolved.INSTANCE, result,
                "unknown-language file returns EmptyResolved, never null");
    }

    @Test
    void resolveReturnsAvailableResolvedForSupportedLanguage() throws ResolutionException {
        StubResolver r = new StubResolver(Set.of("java"));
        r.bootstrap(Path.of("/tmp/project"));

        DiscoveredFile javaFile = new DiscoveredFile(Path.of("Foo.java"), "java", 100);
        Resolved result = r.resolve(javaFile, "fake-cu");

        assertNotSame(EmptyResolved.INSTANCE, result);
        assertTrue(result.isAvailable());
        assertEquals(Confidence.RESOLVED, result.sourceConfidence());
    }

    @Test
    void resolveNeverReturnsNull() throws ResolutionException {
        // Even with a null AST, the contract forbids returning null —
        // the resolver must downgrade to EmptyResolved.
        StubResolver r = new StubResolver(Set.of("java"));
        r.bootstrap(Path.of("/tmp/project"));

        DiscoveredFile javaFile = new DiscoveredFile(Path.of("Foo.java"), "java", 100);
        Resolved result = r.resolve(javaFile, null); // null AST

        assertNotNull(result, "resolve() must never return null");
        assertSame(EmptyResolved.INSTANCE, result,
                "null AST falls back to EmptyResolved");
    }

    @Test
    void shutdownDefaultIsNoOp() {
        // The interface provides a default {} shutdown — verify it runs without
        // throwing on a stub that doesn't override.
        SymbolResolver r = new SymbolResolver() {
            @Override public Set<String> getSupportedLanguages() { return Set.of("java"); }
            @Override public void bootstrap(Path projectRoot) { }
            @Override public Resolved resolve(DiscoveredFile file, Object parsedAst) {
                return EmptyResolved.INSTANCE;
            }
            // shutdown not overridden — uses interface default
        };
        assertDoesNotThrow(r::shutdown);
    }

    @Test
    void bootstrapOnlyCalledOnce_resolverState() throws ResolutionException {
        // A well-formed resolver should idempotently set up its state on a
        // single bootstrap. Verified via the stub's flag.
        StubResolver r = new StubResolver(Set.of("java"));
        assertFalse(r.bootstrapped.get());
        r.bootstrap(Path.of("/tmp/project"));
        assertTrue(r.bootstrapped.get());
    }

    /** Test-only resolver: returns a mock available Resolved for matching languages. */
    private static final class StubResolver implements SymbolResolver {
        private final Set<String> languages;
        final AtomicBoolean bootstrapped = new AtomicBoolean(false);

        StubResolver(Set<String> languages) {
            this.languages = languages;
        }

        @Override public Set<String> getSupportedLanguages() { return languages; }

        @Override
        public void bootstrap(Path projectRoot) {
            bootstrapped.set(true);
        }

        @Override
        public Resolved resolve(DiscoveredFile file, Object parsedAst) {
            if (!languages.contains(file.language())) return EmptyResolved.INSTANCE;
            if (parsedAst == null) return EmptyResolved.INSTANCE;
            return new Resolved() {
                @Override public boolean isAvailable() { return true; }
                @Override public Confidence sourceConfidence() { return Confidence.RESOLVED; }
            };
        }
    }
}
