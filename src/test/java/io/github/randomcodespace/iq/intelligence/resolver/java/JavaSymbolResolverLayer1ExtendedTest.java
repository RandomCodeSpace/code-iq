package io.github.randomcodespace.iq.intelligence.resolver.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.resolution.types.ResolvedType;
import io.github.randomcodespace.iq.intelligence.resolver.ResolutionException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase 7 Layer 1 — additional resolver unit tests covering spec §12 Layer 1
 * cases not exercised by {@link JavaSymbolResolverTest}.
 *
 * <p>Each test bootstraps the resolver against a tiny TempDir tree, then
 * parses an assertion source through the resolver's
 * {@link JavaSymbolResolver#symbolSolver()} and resolves the type of a named
 * field. The point is end-to-end SymbolSolver wiring, not language coverage —
 * if any case here breaks, the resolver is missing a configuration step.
 */
class JavaSymbolResolverLayer1ExtendedTest {

    @TempDir Path repoRoot;
    private JavaSymbolResolver resolver;

    @BeforeEach
    void setUp() throws IOException, ResolutionException {
        Files.writeString(repoRoot.resolve("pom.xml"), "<project/>");
        Files.createDirectories(repoRoot.resolve("src/main/java"));
        resolver = new JavaSymbolResolver(new JavaSourceRootDiscovery());
    }

    // ── Generics (≥3-level nesting per spec) ─────────────────────────────────

    @Test
    void resolvesDeeplyNestedGenericTypeFromJdk() throws Exception {
        // Map<String, List<Set<UUID>>> — all four types are JDK and resolve
        // via ReflectionTypeSolver alone.
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType(
                "import java.util.*; import java.util.UUID; class Z { Map<String, List<Set<UUID>>> deep; }",
                "deep");
        String d = rt.describe();
        assertTrue(d.contains("Map"), "expected Map in " + d);
        assertTrue(d.contains("List"), "expected List in " + d);
        assertTrue(d.contains("Set"), "expected Set in " + d);
        assertTrue(d.contains("UUID"), "expected UUID in " + d);
    }

    // ── Inner classes ───────────────────────────────────────────────────────

    @Test
    void resolvesStaticInnerClass() throws Exception {
        Files.writeString(repoRoot.resolve("src/main/java/Outer.java"),
                "public class Outer { public static class Inner {} }");
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType("class Z { Outer.Inner i; }", "i");
        assertTrue(rt.describe().contains("Outer.Inner"),
                "expected Outer.Inner in " + rt.describe());
    }

    @Test
    void resolvesNonStaticInnerClass() throws Exception {
        Files.writeString(repoRoot.resolve("src/main/java/Outer.java"),
                "public class Outer { public class Inner {} }");
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType("class Z { Outer.Inner i; }", "i");
        assertTrue(rt.describe().contains("Outer.Inner"));
    }

    // ── Records ──────────────────────────────────────────────────────────────

    @Test
    void resolvesRecord() throws Exception {
        Files.writeString(repoRoot.resolve("src/main/java/Pair.java"),
                "public record Pair(String a, int b) {}");
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType("class Z { Pair p; }", "p");
        assertTrue(rt.describe().contains("Pair"));
    }

    // ── Sealed classes ──────────────────────────────────────────────────────

    @Test
    void resolvesSealedHierarchy() throws Exception {
        Files.writeString(repoRoot.resolve("src/main/java/Shape.java"),
                "public sealed interface Shape permits Circle, Square {}");
        Files.writeString(repoRoot.resolve("src/main/java/Circle.java"),
                "public final class Circle implements Shape {}");
        Files.writeString(repoRoot.resolve("src/main/java/Square.java"),
                "public final class Square implements Shape {}");
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType("class Z { Shape s; }", "s");
        assertTrue(rt.describe().contains("Shape"));
    }

    // ── Enum with abstract methods ──────────────────────────────────────────

    @Test
    void resolvesEnumWithAbstractMethods() throws Exception {
        Files.writeString(repoRoot.resolve("src/main/java/Op.java"),
                """
                public enum Op {
                  ADD { @Override public int apply(int a, int b) { return a + b; } };
                  public abstract int apply(int a, int b);
                }
                """);
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType("class Z { Op op; }", "op");
        assertTrue(rt.describe().contains("Op"));
    }

    // ── Interface with default methods ──────────────────────────────────────

    @Test
    void resolvesInterfaceWithDefaultMethod() throws Exception {
        Files.writeString(repoRoot.resolve("src/main/java/Greeter.java"),
                "public interface Greeter { default String hello() { return \"hi\"; } }");
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType("class Z { Greeter g; }", "g");
        assertTrue(rt.describe().contains("Greeter"));
    }

    // ── Abstract class ──────────────────────────────────────────────────────

    @Test
    void resolvesAbstractClass() throws Exception {
        Files.writeString(repoRoot.resolve("src/main/java/Base.java"),
                "public abstract class Base { public abstract void go(); }");
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType("class Z { Base b; }", "b");
        assertTrue(rt.describe().contains("Base"));
    }

    // ── Annotations (definition + use) ──────────────────────────────────────

    @Test
    void resolvesAnnotationType() throws Exception {
        Files.writeString(repoRoot.resolve("src/main/java/Tag.java"),
                "public @interface Tag { String value() default \"\"; }");
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType("class Z { Tag t; }", "t");
        assertTrue(rt.describe().contains("Tag"));
    }

    // ── Same simple name in different packages ──────────────────────────────

    @Test
    void resolvesSameSimpleNameInDifferentPackagesByImport() throws Exception {
        Files.createDirectories(repoRoot.resolve("src/main/java/com/a"));
        Files.createDirectories(repoRoot.resolve("src/main/java/com/b"));
        Files.writeString(repoRoot.resolve("src/main/java/com/a/Foo.java"),
                "package com.a; public class Foo {}");
        Files.writeString(repoRoot.resolve("src/main/java/com/b/Foo.java"),
                "package com.b; public class Foo {}");
        resolver.bootstrap(repoRoot);

        // Importing com.a.Foo pins the resolution.
        ResolvedType rtA = resolveFieldType(
                "package com.x; import com.a.Foo; class Z { Foo f; }", "f");
        assertEquals("com.a.Foo", rtA.asReferenceType().getQualifiedName());

        // Importing com.b.Foo pins the OTHER one — not just whichever happens first.
        ResolvedType rtB = resolveFieldType(
                "package com.x; import com.b.Foo; class Z { Foo f; }", "f");
        assertEquals("com.b.Foo", rtB.asReferenceType().getQualifiedName());
    }

    // ── JDK symbols via ReflectionTypeSolver ────────────────────────────────

    @Test
    void resolvesJdkOptional() throws Exception {
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType(
                "import java.util.Optional; class Z { Optional<String> o; }", "o");
        assertTrue(rt.describe().contains("Optional"));
    }

    @Test
    void resolvesJdkStream() throws Exception {
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType(
                "import java.util.stream.Stream; class Z { Stream<String> s; }", "s");
        assertTrue(rt.describe().contains("Stream"));
    }

    @Test
    void resolvesJdkList() throws Exception {
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType(
                "import java.util.List; class Z { List<Integer> l; }", "l");
        assertTrue(rt.describe().contains("List"));
    }

    // ── Multi-source-root: src/main/java referencing src/test/java ──────────

    @Test
    void resolvesAcrossMainAndTestSourceRoots() throws Exception {
        Files.createDirectories(repoRoot.resolve("src/test/java"));
        Files.writeString(repoRoot.resolve("src/test/java/TestHelper.java"),
                "public class TestHelper {}");
        Files.writeString(repoRoot.resolve("src/main/java/Main.java"),
                "public class Main { TestHelper helper; }");
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType("class Z { TestHelper t; }", "t");
        assertTrue(rt.describe().contains("TestHelper"));
    }

    // ── Wildcard import ─────────────────────────────────────────────────────

    @Test
    void resolvesViaWildcardImport() throws Exception {
        Files.createDirectories(repoRoot.resolve("src/main/java/com/x"));
        Files.writeString(repoRoot.resolve("src/main/java/com/x/Foo.java"),
                "package com.x; public class Foo {}");
        resolver.bootstrap(repoRoot);
        ResolvedType rt = resolveFieldType(
                "package com.y; import com.x.*; class Z { Foo f; }", "f");
        assertEquals("com.x.Foo", rt.asReferenceType().getQualifiedName());
    }

    // ── Cyclic imports (legal in Java) ──────────────────────────────────────

    @Test
    void resolvesCyclicImportsBothDirections() throws Exception {
        Files.createDirectories(repoRoot.resolve("src/main/java/com/cycle"));
        Files.writeString(repoRoot.resolve("src/main/java/com/cycle/A.java"),
                "package com.cycle; public class A { B b; }");
        Files.writeString(repoRoot.resolve("src/main/java/com/cycle/B.java"),
                "package com.cycle; public class B { A a; }");
        resolver.bootstrap(repoRoot);
        ResolvedType rtA = resolveFieldType(
                "package com.cycle; class Z { A a; }", "a");
        ResolvedType rtB = resolveFieldType(
                "package com.cycle; class Z { B b; }", "b");
        assertEquals("com.cycle.A", rtA.asReferenceType().getQualifiedName());
        assertEquals("com.cycle.B", rtB.asReferenceType().getQualifiedName());
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private ResolvedType resolveFieldType(String source, String fieldName) {
        ParserConfiguration cfg = new ParserConfiguration().setSymbolResolver(resolver.symbolSolver());
        CompilationUnit cu = new JavaParser(cfg).parse(source).getResult().orElseThrow();
        return cu.findAll(FieldDeclaration.class).stream()
                .flatMap(f -> f.getVariables().stream())
                .filter(v -> v.getNameAsString().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("field " + fieldName + " not found in source"))
                .getType()
                .resolve();
    }
}
