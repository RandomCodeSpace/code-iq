package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.analyzer.linker.Linker;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import io.github.randomcodespace.iq.detector.Detector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorRegistry;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.intelligence.resolver.EmptyResolved;
import io.github.randomcodespace.iq.intelligence.resolver.ResolverRegistry;
import io.github.randomcodespace.iq.intelligence.resolver.java.JavaResolved;
import io.github.randomcodespace.iq.intelligence.resolver.java.JavaSourceRootDiscovery;
import io.github.randomcodespace.iq.intelligence.resolver.java.JavaSymbolResolver;
import io.github.randomcodespace.iq.model.Confidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Phase 4 pipeline-wiring contract tests for {@link ResolverRegistry} ↔
 * {@link Analyzer}. Covers Tasks 19–21:
 * <ul>
 *   <li>bootstrap is called exactly once per pipeline entry point</li>
 *   <li>per-file {@code resolverFor(language)} is called for each discovered file</li>
 *   <li>the returned {@code Resolved} is threaded onto the {@link DetectorContext}
 *       passed to every detector</li>
 * </ul>
 *
 * <p>These tests exercise the default {@code run()} path. The other two
 * detect-call sites ({@code runBatchedIndex} regular path and {@code
 * analyzeFileRegexOnly}) use the same {@code resolveFor(...)} helper, so the
 * wiring contract is enforced symmetrically.
 */
class AnalyzerResolverWiringTest {

    @TempDir Path tempDir;

    // ── Bootstrap: called exactly once per run() ─────────────────────────────

    @Test
    void bootstrapCalledExactlyOncePerRun() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "public class App {}");

        ResolverRegistry registry = spy(new ResolverRegistry(List.of()));
        Analyzer analyzer = newAnalyzer(registry, captureNothing());
        analyzer.run(tempDir, null);

        verify(registry, times(1)).bootstrap(any(Path.class));
    }

    @Test
    void bootstrapStillCalledOnceWhenManyFilesPresent() throws IOException {
        // Five files — bootstrap fires once, not per-file.
        for (char c : new char[]{'A', 'B', 'C', 'D', 'E'}) {
            Files.writeString(tempDir.resolve(c + ".java"), "public class " + c + " {}");
        }

        ResolverRegistry registry = spy(new ResolverRegistry(List.of()));
        Analyzer analyzer = newAnalyzer(registry, captureNothing());
        analyzer.run(tempDir, null);

        verify(registry, times(1)).bootstrap(any(Path.class));
    }

    @Test
    void bootstrapCalledWithNormalisedAbsolutePath() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "public class App {}");

        ResolverRegistry registry = spy(new ResolverRegistry(List.of()));
        Analyzer analyzer = newAnalyzer(registry, captureNothing());
        analyzer.run(tempDir, null);

        // run() does repoPath.toAbsolutePath().normalize() before bootstrap.
        Path expected = tempDir.toAbsolutePath().normalize();
        verify(registry).bootstrap(expected);
    }

    @Test
    void bootstrapInvokedEvenWhenRepoHasNoFiles() {
        // Empty dir — bootstrap should still happen (and the rest of the
        // pipeline should still complete cleanly).
        ResolverRegistry registry = spy(new ResolverRegistry(List.of()));
        Analyzer analyzer = newAnalyzer(registry, captureNothing());
        analyzer.run(tempDir, null);

        verify(registry, times(1)).bootstrap(any(Path.class));
    }

    // ── Per-file: resolverFor(language) is called ────────────────────────────

    @Test
    void resolverForCalledForJavaLanguage() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "public class App {}");

        ResolverRegistry registry = spy(new ResolverRegistry(List.of()));
        Analyzer analyzer = newAnalyzer(registry, captureNothing());
        analyzer.run(tempDir, null);

        verify(registry, atLeastOnce()).resolverFor("java");
    }

    // ── DetectorContext: ctx.resolved() reflects the resolver result ─────────

    @Test
    void ctxResolvedIsPresentForJavaFile() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "public class App {}");

        AtomicReference<DetectorContext> seen = new AtomicReference<>();
        Analyzer analyzer = newAnalyzer(new ResolverRegistry(List.of()), captureCtx(seen));
        analyzer.run(tempDir, null);

        DetectorContext ctx = seen.get();
        assertNotNull(ctx, "test detector must have been called for App.java");
        assertTrue(ctx.resolved().isPresent(),
                "ctx.resolved() must be Optional.of(...) after the wiring — never empty");
        assertSame(EmptyResolved.INSTANCE, ctx.resolved().get(),
                "no resolver registered for 'java' → NOOP resolver → EmptyResolved.INSTANCE");
    }

    @Test
    void ctxResolvedIsEmptyResolvedForFileWithoutResolver() throws IOException {
        // Even with no resolver registered for any language, ctx.resolved()
        // is the singleton EmptyResolved — never Optional.empty(), never null.
        Files.writeString(tempDir.resolve("Foo.java"), "public class Foo {}");

        AtomicReference<DetectorContext> seen = new AtomicReference<>();
        Analyzer analyzer = newAnalyzer(new ResolverRegistry(List.of()), captureCtx(seen));
        analyzer.run(tempDir, null);

        DetectorContext ctx = seen.get();
        assertNotNull(ctx);
        assertSame(EmptyResolved.INSTANCE, ctx.resolved().orElseThrow(),
                "EmptyResolved is the only legal fallback — JavaDetector tests rely on this");
    }

    // ── DetectorContext: with JavaSymbolResolver registered, Java carries JavaResolved

    @Test
    void javaFilePicksUpJavaResolvedWhenResolverRegistered() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "public class App {}");

        AtomicReference<DetectorContext> seen = new AtomicReference<>();
        ResolverRegistry registry = new ResolverRegistry(
                List.of(new JavaSymbolResolver(new JavaSourceRootDiscovery())));
        Analyzer analyzer = newAnalyzer(registry, captureCtx(seen));
        analyzer.run(tempDir, null);

        DetectorContext ctx = seen.get();
        assertNotNull(ctx, "test detector must have been called");
        assertTrue(ctx.resolved().isPresent(), "wiring must populate ctx.resolved()");

        var resolved = ctx.resolved().orElseThrow();
        assertNotSame(EmptyResolved.INSTANCE, resolved,
                "with JavaSymbolResolver registered, Java files get JavaResolved");
        assertInstanceOf(JavaResolved.class, resolved);

        JavaResolved jr = (JavaResolved) resolved;
        assertTrue(jr.isAvailable(), "JavaResolved is the RESOLVED tier — always available");
        assertEquals(Confidence.RESOLVED, jr.sourceConfidence());
        assertNotNull(jr.cu(), "the CU is the lazy-parsed App.java");
        assertNotNull(jr.solver(), "the symbol solver is threaded through");
    }

    @Test
    void wiringIsBackwardCompatibleWithLegacyCtor() throws IOException {
        // The 6-arg backward-compat ctor must still produce a working Analyzer
        // whose detectors see a populated ctx.resolved() (Optional.of(EmptyResolved)).
        Files.writeString(tempDir.resolve("App.java"), "public class App {}");

        AtomicReference<DetectorContext> seen = new AtomicReference<>();
        Detector capture = captureCtx(seen);
        Analyzer analyzer = new Analyzer(
                new DetectorRegistry(List.of(capture)),
                new StructuredParser(),
                new FileDiscovery(new CodeIqConfig()),
                new LayerClassifier(),
                List.<Linker>of(),
                new CodeIqConfig());
        analyzer.run(tempDir, null);

        DetectorContext ctx = seen.get();
        assertNotNull(ctx, "test detector must have been called via legacy ctor");
        assertTrue(ctx.resolved().isPresent(),
                "legacy ctor must still wire a default ResolverRegistry");
        assertSame(EmptyResolved.INSTANCE, ctx.resolved().orElseThrow());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Analyzer newAnalyzer(ResolverRegistry registry, Detector detector) {
        return new Analyzer(
                new DetectorRegistry(List.of(detector)),
                new StructuredParser(),
                new FileDiscovery(new CodeIqConfig()),
                new LayerClassifier(),
                List.<Linker>of(),
                new CodeIqConfig(),
                CodeIqUnifiedConfig.empty(),
                new ConfigScanner(),
                new ArchitectureKeywordFilter(),
                registry
        );
    }

    private Detector captureNothing() {
        return captureCtx(new AtomicReference<>());
    }

    private Detector captureCtx(AtomicReference<DetectorContext> seen) {
        return new Detector() {
            @Override public String getName() { return "test-capture-detector"; }
            @Override public Set<String> getSupportedLanguages() { return Set.of("java"); }
            @Override public DetectorResult detect(DetectorContext ctx) {
                seen.set(ctx);
                return DetectorResult.empty();
            }
        };
    }
}
