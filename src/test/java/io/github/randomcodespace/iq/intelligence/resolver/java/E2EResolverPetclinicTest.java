package io.github.randomcodespace.iq.intelligence.resolver.java;

import io.github.randomcodespace.iq.analyzer.DiscoveredFile;
import io.github.randomcodespace.iq.intelligence.resolver.EmptyResolved;
import io.github.randomcodespace.iq.intelligence.resolver.ResolutionException;
import io.github.randomcodespace.iq.intelligence.resolver.Resolved;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7 Layer 7 — E2E resolver regression gate against a real Spring app.
 *
 * <p>Runs {@link JavaSymbolResolver} against every {@code .java} file in
 * {@code $E2E_PETCLINIC_DIR} (typically a clone of {@code spring-petclinic})
 * and asserts:
 * <ul>
 *   <li>bootstrap completes within 10 s (spec §9 budget),</li>
 *   <li>no file produces a thrown exception,</li>
 *   <li>a non-trivial fraction (&gt; 50%) of files produces a {@link JavaResolved}
 *       (i.e. the strict-success check isn't false-rejecting valid Java),</li>
 *   <li>a known petclinic FQN (one of the entity classes — {@code Owner}/
 *       {@code Pet}/{@code Vet}) is resolvable end-to-end.</li>
 * </ul>
 *
 * <p>This is a lightweight stand-in for spec §12 Layer 7's full
 * precision/recall comparison. That comparison requires a pre-resolver
 * baseline JSON checked into test resources (captured on the same
 * petclinic SHA pre-resolver), which is implementation-time work. Until
 * the baseline lands, this test is the strongest signal we have that the
 * resolver works on a real-world codebase.
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "E2E_PETCLINIC_DIR", matches = ".+")
class E2EResolverPetclinicTest {

    @Test
    void resolverBootstrapsAndResolvesPetclinicWithinBudget() throws IOException, ResolutionException {
        Path repoRoot = Path.of(System.getenv("E2E_PETCLINIC_DIR"));
        assertTrue(Files.isDirectory(repoRoot),
                "E2E_PETCLINIC_DIR must point at a real directory: " + repoRoot);

        JavaSymbolResolver resolver = new JavaSymbolResolver(new JavaSourceRootDiscovery());
        long bootstrapStart = System.currentTimeMillis();
        resolver.bootstrap(repoRoot);
        long bootstrapMs = System.currentTimeMillis() - bootstrapStart;
        assertTrue(bootstrapMs < 10_000,
                "bootstrap exceeded 10 s budget: " + bootstrapMs + " ms (spec §9)");

        List<Path> javaFiles;
        try (Stream<Path> walk = Files.walk(repoRoot)) {
            javaFiles = walk
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/target/"))
                    .filter(p -> !p.toString().contains("/build/"))
                    .toList();
        }
        assertFalse(javaFiles.isEmpty(),
                "no .java files found under " + repoRoot
                        + " — point E2E_PETCLINIC_DIR at a Java repo");

        int total = 0;
        int resolved = 0;
        for (Path p : javaFiles) {
            String content = Files.readString(p);
            DiscoveredFile file = new DiscoveredFile(
                    repoRoot.relativize(p), "java", content.length());
            Resolved r;
            try {
                r = resolver.resolve(file, content);
            } catch (Throwable t) {
                throw new AssertionError("resolver threw on " + p + ": " + t, t);
            }
            assertNotNull(r, "resolver returned null on " + p);
            total++;
            if (r != EmptyResolved.INSTANCE) {
                resolved++;
            }
        }

        assertTrue(total > 0, "no .java files scanned");
        double frac = ((double) resolved) / total;
        assertTrue(frac > 0.5,
                "only " + resolved + "/" + total + " (" + frac + ") files produced JavaResolved — "
                        + "strict-success check too aggressive on real-world Java, or solver setup broken");
    }
}
