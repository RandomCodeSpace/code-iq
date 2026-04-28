package io.github.randomcodespace.iq.intelligence.resolver.java;

import io.github.randomcodespace.iq.analyzer.DiscoveredFile;
import io.github.randomcodespace.iq.intelligence.resolver.EmptyResolved;
import io.github.randomcodespace.iq.intelligence.resolver.ResolutionException;
import io.github.randomcodespace.iq.intelligence.resolver.Resolved;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7 Layer 4 — pathological / memory-pressure inputs.
 *
 * <p>Spec §12 Layer 4 cases:
 * <ul>
 *   <li>10K-line synthetic class.</li>
 *   <li>File with 1000 imports (most unresolvable).</li>
 *   <li>10-deep generic nesting.</li>
 * </ul>
 *
 * <p>The hard contract is "no exception, never null" — Surefire's default heap
 * covers the spec's {@code -Xmx512m} target several times over, so we don't
 * pin it explicitly. Per-test wall-clock {@code @Timeout} is the regression
 * sentinel: if a future change makes JavaSymbolSolver memoization quadratic,
 * the timeout trips before OOM does.
 */
class JavaSymbolResolverPathologicalTest {

    @TempDir Path repoRoot;
    private JavaSymbolResolver resolver;

    @BeforeEach
    void setUp() throws IOException, ResolutionException {
        Files.writeString(repoRoot.resolve("pom.xml"), "<project/>");
        Files.createDirectories(repoRoot.resolve("src/main/java"));
        resolver = new JavaSymbolResolver(new JavaSourceRootDiscovery());
        resolver.bootstrap(repoRoot);
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void tenThousandLineClassResolvesWithinBudget() {
        StringBuilder src = new StringBuilder("package x; public class Big {\n");
        for (int i = 0; i < 10_000; i++) {
            src.append("    public int m").append(i).append("() { return ").append(i).append("; }\n");
        }
        src.append("}\n");

        DiscoveredFile file = new DiscoveredFile(
                Path.of("src/main/java/x/Big.java"), "java", src.length());
        Resolved r = resolver.resolve(file, src.toString());
        assertNotNull(r, "10K-line class must not return null");
        // Both JavaResolved (parser succeeded) and EmptyResolved (strict-success
        // tripped) are legal — the contract is "no exception, no null".
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void fileWithThousandImportsResolvesWithinBudget() {
        StringBuilder src = new StringBuilder("package x;\n");
        for (int i = 0; i < 1_000; i++) {
            // Most of these point at packages that don't exist. JavaParser is
            // permissive at the syntax layer (it accepts the import); the
            // symbol solver later fails to resolve them but resolve() still
            // returns. The pathology is the symbol solver's memo footprint.
            src.append("import com.nonexistent.pkg").append(i).append(".Foo").append(i).append(";\n");
        }
        src.append("public class Imp {}\n");

        DiscoveredFile file = new DiscoveredFile(
                Path.of("src/main/java/x/Imp.java"), "java", src.length());
        Resolved r = resolver.resolve(file, src.toString());
        assertNotNull(r);
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void tenDeepGenericNestingResolvesWithinBudget() {
        // Built programmatically so the bracket count is provably balanced —
        // hand-counted literals are a notorious source of off-by-one bugs.
        int depth = 10;
        StringBuilder src = new StringBuilder();
        src.append("import java.util.List; import java.util.UUID; class Z { ");
        for (int i = 0; i < depth; i++) src.append("List<");
        src.append("UUID");
        for (int i = 0; i < depth; i++) src.append(">");
        src.append(" deep; }");

        DiscoveredFile file = new DiscoveredFile(
                Path.of("src/main/java/Z.java"), "java", src.length());
        Resolved r = resolver.resolve(file, src.toString());
        assertNotNull(r);
        assertNotSame(EmptyResolved.INSTANCE, r,
                "deep generics still parse — JavaParser handles arbitrary nesting");
    }
}
