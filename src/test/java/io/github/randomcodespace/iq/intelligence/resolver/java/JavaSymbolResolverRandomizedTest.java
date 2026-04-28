package io.github.randomcodespace.iq.intelligence.resolver.java;

import io.github.randomcodespace.iq.analyzer.DiscoveredFile;
import io.github.randomcodespace.iq.intelligence.resolver.ResolutionException;
import io.github.randomcodespace.iq.intelligence.resolver.Resolved;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7 Layer 8 — hand-rolled randomized testing.
 *
 * <p>Per the implementation plan (Task 35), jqwik (the recommended
 * property-based test library) is EPL-2.0 — not on the project's
 * preferred-license list (MIT/Apache/BSD per
 * {@code ~/.claude/rules/dependencies.md}). The plan's documented fallback
 * is "hand-rolled randomized generators using existing JUnit +
 * {@link Random}" and that's what lands here.
 *
 * <p>Properties exercised over a fixed-seed corpus of {@value #N_SAMPLES}
 * generated files:
 * <ul>
 *   <li>{@code resolve()} never throws unchecked.</li>
 *   <li>{@code resolve()} never returns null.</li>
 *   <li>{@code resolve()} completes per file within a generous wall-clock
 *       budget — production budget is 500 ms (spec §9
 *       {@code max_per_file_resolve_ms}); we use 1 s here to absorb CI
 *       variance.</li>
 * </ul>
 *
 * <p>Seed is fixed so failures are reproducible. To explore a different
 * region of input space, change {@link #SEED} and re-run.
 */
class JavaSymbolResolverRandomizedTest {

    private static final int N_SAMPLES = 100;
    private static final long SEED = 0xC0DE19_70_42L; // change to explore
    private static final long PER_FILE_BUDGET_MS = 1_000;

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
    void randomizedJavaSourcesNeverThrowAndCompleteUnderBudget() {
        Random rnd = new Random(SEED);
        long globalStartNs = System.nanoTime();
        for (int i = 0; i < N_SAMPLES; i++) {
            String src = generateRandomJava(rnd, i);
            DiscoveredFile file = new DiscoveredFile(
                    Path.of("src/main/java/Gen" + i + ".java"), "java", src.length());
            long startNs = System.nanoTime();
            Resolved r;
            try {
                r = resolver.resolve(file, src);
            } catch (Throwable t) {
                fail("sample #" + i + " threw " + t.getClass().getSimpleName()
                        + " on source:\n" + src, t);
                return;
            }
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            assertNotNull(r, "sample #" + i + " returned null");
            assertTrue(durationMs < PER_FILE_BUDGET_MS,
                    "sample #" + i + " took " + durationMs + " ms (>"
                            + PER_FILE_BUDGET_MS + " ms budget)\nsource:\n" + src);
        }
        long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - globalStartNs);
        // Soft sanity: total wall time well under N × budget — no single file
        // pegging the loop. (Median per-file time should be ≪ budget.)
        assertTrue(totalMs < N_SAMPLES * PER_FILE_BUDGET_MS,
                "total time " + totalMs + " ms exceeded global budget");
    }

    /**
     * Minimal generator: small classes with a varying mix of fields, methods,
     * and imports. Not exhaustive — diverse enough to surface obvious panics
     * in {@code resolve()}.
     */
    private static String generateRandomJava(Random rnd, int idx) {
        StringBuilder src = new StringBuilder();
        src.append("package gen;\n");
        // Random imports — mix of resolvable JDK types and unresolvable ones,
        // so the generated corpus exercises both paths through the symbol solver.
        String[] importPool = {
                "java.util.List",
                "java.util.Map",
                "java.util.Set",
                "java.util.UUID",
                "java.util.Optional",
                "com.absent.Absent" + idx,
        };
        int nImports = rnd.nextInt(5);
        for (int i = 0; i < nImports; i++) {
            src.append("import ").append(importPool[rnd.nextInt(importPool.length)]).append(";\n");
        }
        src.append("public class Gen").append(idx).append(" {\n");
        int nFields = rnd.nextInt(8);
        for (int i = 0; i < nFields; i++) {
            String type = randomType(rnd);
            src.append("    private ").append(type).append(" f").append(i).append(";\n");
        }
        int nMethods = rnd.nextInt(8);
        for (int i = 0; i < nMethods; i++) {
            String returnType = randomType(rnd);
            src.append("    public ").append(returnType)
                    .append(" m").append(i).append("() { return ")
                    .append(defaultFor(returnType)).append("; }\n");
        }
        src.append("}\n");
        return src.toString();
    }

    private static String randomType(Random rnd) {
        return switch (rnd.nextInt(6)) {
            case 0 -> "int";
            case 1 -> "String";
            case 2 -> "java.util.List<String>";
            case 3 -> "java.util.Map<String, Integer>";
            case 4 -> "java.util.Optional<java.util.UUID>";
            default -> "java.util.Set<java.util.List<String>>";
        };
    }

    private static String defaultFor(String type) {
        if ("int".equals(type)) return "0";
        return "null";
    }
}
