package io.github.randomcodespace.iq.intelligence.resolver;

import io.github.randomcodespace.iq.analyzer.DiscoveredFile;
import io.github.randomcodespace.iq.model.Confidence;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aggressive coverage for {@link ResolverRegistry}. Exercises the determinism,
 * conflict resolution, case-insensitivity, null tolerance, and per-resolver
 * failure isolation contracts.
 */
class ResolverRegistryTest {

    // ---------- Lookup ----------

    @Test
    void emptyRegistryReturnsNoopForAnyLanguage() throws ResolutionException {
        ResolverRegistry registry = new ResolverRegistry(List.of());
        SymbolResolver r = registry.resolverFor("java");
        assertSame(ResolverRegistry.NOOP, r);

        // The NOOP must always return EmptyResolved
        Resolved result = r.resolve(new DiscoveredFile(Path.of("Foo.java"), "java", 100), "ast");
        assertSame(EmptyResolved.INSTANCE, result);
    }

    @Test
    void singleResolverIsReturnedForItsLanguage() {
        AStubResolver java = new AStubResolver("java");
        ResolverRegistry registry = new ResolverRegistry(List.of(java));
        assertSame(java, registry.resolverFor("java"));
    }

    @Test
    void unknownLanguageReturnsNoop() {
        AStubResolver java = new AStubResolver("java");
        ResolverRegistry registry = new ResolverRegistry(List.of(java));
        assertSame(ResolverRegistry.NOOP, registry.resolverFor("python"));
    }

    @Test
    void languageLookupIsCaseInsensitive() {
        AStubResolver java = new AStubResolver("java");
        ResolverRegistry registry = new ResolverRegistry(List.of(java));
        assertSame(java, registry.resolverFor("Java"));
        assertSame(java, registry.resolverFor("JAVA"));
        assertSame(java, registry.resolverFor("jAvA"));
    }

    @Test
    void nullLanguageReturnsNoopWithoutNpe() {
        AStubResolver java = new AStubResolver("java");
        ResolverRegistry registry = new ResolverRegistry(List.of(java));
        // Defensive: null is a sentinel, not an error
        assertSame(ResolverRegistry.NOOP, registry.resolverFor(null));
    }

    @Test
    void resolverForNeverReturnsNull() {
        ResolverRegistry registry = new ResolverRegistry(List.of());
        assertNotNull(registry.resolverFor("java"));
        assertNotNull(registry.resolverFor("python"));
        assertNotNull(registry.resolverFor(""));
        assertNotNull(registry.resolverFor("\t\n"));
    }

    @Test
    void blankLanguageReturnsNoop() {
        // Detector contract: getSupportedLanguages should never include blank/empty strings.
        // The registry defensively skips them so a misbehaving resolver doesn't poison
        // lookup for "" .
        AStubResolver java = new AStubResolver("java");
        ResolverRegistry registry = new ResolverRegistry(List.of(java));
        assertSame(ResolverRegistry.NOOP, registry.resolverFor(""));
        assertSame(ResolverRegistry.NOOP, registry.resolverFor("   "));
    }

    // ---------- Conflict resolution ----------

    @Test
    void duplicateLanguageFirstSortedWins() {
        // Two resolvers both claim "java". Sort by class simple name — A before Z.
        AStubResolver a = new AStubResolver("java");
        ZStubResolver z = new ZStubResolver("java");
        ResolverRegistry registry = new ResolverRegistry(List.of(z, a)); // input order intentionally reversed

        assertSame(a, registry.resolverFor("java"),
                "first-in-sort-order wins — AStubResolver < ZStubResolver alphabetically");
    }

    // ---------- Order ----------

    @Test
    void allReturnsSortedOrder() {
        AStubResolver a = new AStubResolver("a");
        ZStubResolver z = new ZStubResolver("z");
        MStubResolver m = new MStubResolver("m");
        ResolverRegistry registry = new ResolverRegistry(List.of(z, a, m));

        List<SymbolResolver> all = registry.all();
        assertEquals(3, all.size());
        assertSame(a, all.get(0));
        assertSame(m, all.get(1));
        assertSame(z, all.get(2));
    }

    // ---------- Bootstrap ----------

    @Test
    void bootstrapCallsEveryResolverInOrder() {
        List<String> calledOrder = new ArrayList<>();
        AStubResolver a = new AStubResolver("a", () -> calledOrder.add("A"));
        MStubResolver m = new MStubResolver("m", () -> calledOrder.add("M"));
        ZStubResolver z = new ZStubResolver("z", () -> calledOrder.add("Z"));
        ResolverRegistry registry = new ResolverRegistry(List.of(z, m, a)); // input order shuffled

        registry.bootstrap(Path.of("/tmp/project"));

        assertEquals(List.of("A", "M", "Z"), calledOrder,
                "bootstrap iterates in alphabetical order — determinism guarantee");
    }

    @Test
    void bootstrapResilient_oneFailureDoesNotBlockOthers() {
        AtomicInteger aCalled = new AtomicInteger();
        AtomicInteger zCalled = new AtomicInteger();
        AStubResolver a = new AStubResolver("a", () -> {
            aCalled.incrementAndGet();
            throw new RuntimeException("simulated bootstrap failure");
        });
        ZStubResolver z = new ZStubResolver("z", zCalled::incrementAndGet);
        ResolverRegistry registry = new ResolverRegistry(List.of(a, z));

        // Must not throw — failure is swallowed and logged
        assertDoesNotThrow(() -> registry.bootstrap(Path.of("/tmp/project")));

        assertEquals(1, aCalled.get(), "failing resolver was called");
        assertEquals(1, zCalled.get(),
                "subsequent resolvers run despite earlier failure — resilience guarantee");
    }

    @Test
    void bootstrapResilient_resolutionExceptionAlsoSwallowed() {
        AtomicInteger zCalled = new AtomicInteger();
        SymbolResolver throwing = new SymbolResolver() {
            @Override public Set<String> getSupportedLanguages() { return Set.of("a"); }
            @Override public void bootstrap(Path projectRoot) throws ResolutionException {
                throw new ResolutionException("simulated checked failure", projectRoot, "a");
            }
            @Override public Resolved resolve(DiscoveredFile file, Object parsedAst) {
                return EmptyResolved.INSTANCE;
            }
        };
        ZStubResolver z = new ZStubResolver("z", zCalled::incrementAndGet);
        ResolverRegistry registry = new ResolverRegistry(List.of(throwing, z));

        assertDoesNotThrow(() -> registry.bootstrap(Path.of("/tmp/project")));
        assertEquals(1, zCalled.get(),
                "ResolutionException from one resolver does not stop the pass");
    }

    @Test
    void bootstrapEmptyRegistryIsNoOp() {
        ResolverRegistry registry = new ResolverRegistry(List.of());
        assertDoesNotThrow(() -> registry.bootstrap(Path.of("/tmp/project")));
    }

    // ---------- Test stubs ----------

    /** Resolves one language. Optional bootstrap callback for sequencing tests. */
    private static class AStubResolver implements SymbolResolver {
        private final String language;
        private final Runnable onBootstrap;
        AStubResolver(String language) { this(language, () -> {}); }
        AStubResolver(String language, Runnable onBootstrap) {
            this.language = language;
            this.onBootstrap = onBootstrap;
        }
        @Override public Set<String> getSupportedLanguages() { return Set.of(language); }
        @Override public void bootstrap(Path projectRoot) { onBootstrap.run(); }
        @Override public Resolved resolve(DiscoveredFile file, Object parsedAst) {
            return new Resolved() {
                @Override public boolean isAvailable() { return true; }
                @Override public Confidence sourceConfidence() { return Confidence.RESOLVED; }
            };
        }
    }

    private static final class MStubResolver extends AStubResolver {
        MStubResolver(String language) { super(language); }
        MStubResolver(String language, Runnable onBootstrap) { super(language, onBootstrap); }
    }

    private static final class ZStubResolver extends AStubResolver {
        ZStubResolver(String language) { super(language); }
        ZStubResolver(String language, Runnable onBootstrap) { super(language, onBootstrap); }
    }
}
