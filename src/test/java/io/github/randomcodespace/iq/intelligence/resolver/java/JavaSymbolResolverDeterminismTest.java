package io.github.randomcodespace.iq.intelligence.resolver.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.resolution.types.ResolvedType;
import io.github.randomcodespace.iq.analyzer.DiscoveredFile;
import io.github.randomcodespace.iq.intelligence.resolver.ResolutionException;
import io.github.randomcodespace.iq.intelligence.resolver.Resolved;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7 Layer 6 — determinism gate for the symbol resolver.
 *
 * <p>The graph-build determinism contract (same input → byte-identical graph,
 * every run) extends to the resolver: same project root + same source content
 * must produce the same {@link Resolved} shape, and the same field/type
 * reference must resolve to the same FQN every time.
 *
 * <p>Tested invariants:
 * <ol>
 *   <li>Same source string resolved N times → identical resolved FQN.</li>
 *   <li>Two independent resolver instances over the same project root →
 *       identical resolved FQN for the same source.</li>
 *   <li>Re-bootstrap on the same root → identical resolution behaviour
 *       (the registry-side determinism guarantee, but checked at the resolver
 *       boundary too).</li>
 * </ol>
 */
class JavaSymbolResolverDeterminismTest {

    @TempDir Path repoRoot;

    private static final String PET_PATH = "src/main/java/com/example/Pet.java";
    private static final String PET_SOURCE = """
            package com.example;
            import com.example.a.Owner;
            public class Pet {
                private Owner owner;
            }
            """;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(repoRoot.resolve("src/main/java/com/example/a"));
        Files.writeString(repoRoot.resolve("pom.xml"), "<project/>");
        Files.writeString(repoRoot.resolve("src/main/java/com/example/a/Owner.java"),
                """
                package com.example.a;
                public class Owner {}
                """);
        Path absPet = repoRoot.resolve(PET_PATH);
        Files.createDirectories(absPet.getParent());
        Files.writeString(absPet, PET_SOURCE);
    }

    @Test
    void sameInputResolvesToSameFqnEveryTime() throws ResolutionException {
        JavaSymbolResolver resolver = new JavaSymbolResolver(new JavaSourceRootDiscovery());
        resolver.bootstrap(repoRoot);
        DiscoveredFile file = new DiscoveredFile(Path.of(PET_PATH), "java", PET_SOURCE.length());

        // Resolve 25 times — every call must produce the same FQN. JavaParser's
        // identity-not-value semantics means the JavaResolved instances differ,
        // but the resolved type's FQN must be stable.
        List<String> fqns = IntStream.range(0, 25)
                .mapToObj(i -> {
                    Resolved r = resolver.resolve(file, PET_SOURCE);
                    return ownerFieldFqn((JavaResolved) r);
                })
                .toList();

        // All elements are the same FQN.
        String first = fqns.get(0);
        assertEquals("com.example.a.Owner", first,
                "first resolution must pin the imported Owner FQN");
        for (int i = 1; i < fqns.size(); i++) {
            assertEquals(first, fqns.get(i),
                    "resolution #" + i + " diverged — determinism gate broken");
        }
    }

    @Test
    void twoResolverInstancesOverSameProjectAgree() throws ResolutionException {
        JavaSymbolResolver a = new JavaSymbolResolver(new JavaSourceRootDiscovery());
        JavaSymbolResolver b = new JavaSymbolResolver(new JavaSourceRootDiscovery());
        a.bootstrap(repoRoot);
        b.bootstrap(repoRoot);

        DiscoveredFile file = new DiscoveredFile(Path.of(PET_PATH), "java", PET_SOURCE.length());

        String fqnA = ownerFieldFqn((JavaResolved) a.resolve(file, PET_SOURCE));
        String fqnB = ownerFieldFqn((JavaResolved) b.resolve(file, PET_SOURCE));

        assertEquals("com.example.a.Owner", fqnA);
        assertEquals(fqnA, fqnB, "two independent resolver instances must agree on the FQN");
    }

    @Test
    void rebootstrapStillProducesSameFqn() throws ResolutionException {
        // The contract: rebootstrap is allowed (idempotent in observable
        // behaviour). After a second bootstrap on the same root, the resolver
        // resolves the same input the same way.
        JavaSymbolResolver resolver = new JavaSymbolResolver(new JavaSourceRootDiscovery());

        resolver.bootstrap(repoRoot);
        DiscoveredFile file = new DiscoveredFile(Path.of(PET_PATH), "java", PET_SOURCE.length());
        String first = ownerFieldFqn((JavaResolved) resolver.resolve(file, PET_SOURCE));

        resolver.bootstrap(repoRoot); // second bootstrap on same root
        String second = ownerFieldFqn((JavaResolved) resolver.resolve(file, PET_SOURCE));

        assertEquals("com.example.a.Owner", first);
        assertEquals(first, second, "rebootstrap must not change resolution behaviour");
    }

    @Test
    void deeperFqnsAreAlsoStable() throws Exception {
        // Add a slightly deeper hierarchy to widen the determinism check —
        // the test is small enough that a divergence on a 1-level lookup
        // could hide one on a 2-level one.
        Files.createDirectories(repoRoot.resolve("src/main/java/com/example/inner/deep"));
        Files.writeString(repoRoot.resolve("src/main/java/com/example/inner/deep/Marker.java"),
                """
                package com.example.inner.deep;
                public class Marker {}
                """);
        String depPath = "src/main/java/com/example/Dep.java";
        String depSource = """
                package com.example;
                import com.example.inner.deep.Marker;
                public class Dep {
                    private Marker marker;
                }
                """;
        Files.writeString(repoRoot.resolve(depPath), depSource);

        JavaSymbolResolver resolver = new JavaSymbolResolver(new JavaSourceRootDiscovery());
        resolver.bootstrap(repoRoot);
        DiscoveredFile file = new DiscoveredFile(Path.of(depPath), "java", depSource.length());

        for (int i = 0; i < 10; i++) {
            JavaResolved r = (JavaResolved) resolver.resolve(file, depSource);
            assertEquals("com.example.inner.deep.Marker",
                    fieldFqn(r, "marker"),
                    "deep FQN diverged on iteration " + i);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Resolve the Pet.owner field's declared-type FQN via the carried solver. */
    private static String ownerFieldFqn(JavaResolved r) {
        return fieldFqn(r, "owner");
    }

    private static String fieldFqn(JavaResolved r, String fieldName) {
        CompilationUnit cu = r.cu();
        ClassOrInterfaceDeclaration cls = cu.findFirst(ClassOrInterfaceDeclaration.class)
                .orElseThrow();
        return cls.getFields().stream()
                .filter(f -> f.getVariables().stream()
                        .anyMatch(v -> v.getNameAsString().equals(fieldName)))
                .findFirst()
                .map(f -> f.getVariable(0).getType())
                .filter(t -> t.isClassOrInterfaceType())
                .map(t -> resolveFqn(t.asClassOrInterfaceType()))
                .orElseThrow(() -> new AssertionError("field '" + fieldName + "' not found"));
    }

    private static String resolveFqn(ClassOrInterfaceType type) {
        ResolvedType rt = type.resolve();
        return rt.isReferenceType()
                ? rt.asReferenceType().getQualifiedName()
                : rt.describe();
    }
}
