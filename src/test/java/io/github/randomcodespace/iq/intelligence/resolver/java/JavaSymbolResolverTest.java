package io.github.randomcodespace.iq.intelligence.resolver.java;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.resolution.types.ResolvedType;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import io.github.randomcodespace.iq.analyzer.DiscoveredFile;
import io.github.randomcodespace.iq.intelligence.resolver.EmptyResolved;
import io.github.randomcodespace.iq.intelligence.resolver.ResolutionException;
import io.github.randomcodespace.iq.intelligence.resolver.Resolved;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Layer 1 unit tests for {@link JavaSymbolResolver}.
 *
 * <p>Covers all the contract obligations of the SPI plus a smoke test that
 * the solver actually resolves a basic type after bootstrap. Deeper resolution
 * scenarios (cross-file type lookups, generics, inner classes) are exercised
 * by the integration / E2E tests once detectors migrate.
 */
class JavaSymbolResolverTest {

    private JavaSymbolResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new JavaSymbolResolver(new JavaSourceRootDiscovery());
    }

    // ---------- Language declaration ----------

    @Test
    void supportsJavaOnly() {
        assertEquals(Set.of("java"), resolver.getSupportedLanguages());
    }

    // ---------- Bootstrap ----------

    @Test
    void bootstrapEmptyProjectStillBuildsReflectionSolver(@TempDir Path tmp) throws ResolutionException {
        // No source roots — combined solver still has ReflectionTypeSolver.
        resolver.bootstrap(tmp);
        CombinedTypeSolver cts = resolver.combinedTypeSolver();
        assertNotNull(cts, "combinedTypeSolver is non-null after bootstrap");
        // ReflectionTypeSolver alone — but solver can still resolve java.lang.String.
        assertSolverResolvesString(resolver);
    }

    @Test
    void bootstrapWithSourceRootsAddsJavaParserTypeSolvers(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(tmp.resolve("src/main/java/Foo.java"), "public class Foo {}");
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");

        resolver.bootstrap(tmp);

        assertNotNull(resolver.combinedTypeSolver());
        // After bootstrap with source root, solver resolves Foo from that root.
        assertSolverResolvesType(resolver, "public class Bar { Foo f; }",
                "Foo", "Foo");
    }

    @Test
    void bootstrapTwiceIsIdempotent(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/java"));
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");

        resolver.bootstrap(tmp);
        CombinedTypeSolver firstCts = resolver.combinedTypeSolver();
        resolver.bootstrap(tmp);
        CombinedTypeSolver secondCts = resolver.combinedTypeSolver();

        assertNotNull(firstCts);
        assertNotNull(secondCts);
        // Two bootstraps on the same project should produce equivalent state
        // (different instances but same wiring).
        assertNotSame(firstCts, secondCts, "bootstrap creates a fresh CombinedTypeSolver each call");
    }

    @Test
    void combinedTypeSolverIsNullBeforeBootstrap() {
        assertNull(resolver.combinedTypeSolver());
    }

    // ---------- resolve() — empty / fallback paths ----------

    @Test
    void resolveBeforeBootstrapReturnsEmpty() {
        DiscoveredFile f = new DiscoveredFile(Path.of("Foo.java"), "java", 100);
        Resolved r = resolver.resolve(f, parse("class Foo {}"));
        assertSame(EmptyResolved.INSTANCE, r,
                "no bootstrap → no solver → EmptyResolved (graceful fallback)");
    }

    @Test
    void resolveNullFileReturnsEmpty() throws ResolutionException {
        resolver.bootstrap(Path.of(System.getProperty("java.io.tmpdir")));
        Resolved r = resolver.resolve(null, parse("class Foo {}"));
        assertSame(EmptyResolved.INSTANCE, r);
    }

    @Test
    void resolveNonJavaFileReturnsEmpty(@TempDir Path tmp) throws ResolutionException {
        resolver.bootstrap(tmp);
        DiscoveredFile py = new DiscoveredFile(Path.of("foo.py"), "python", 100);
        Resolved r = resolver.resolve(py, parse("class Foo {}"));
        assertSame(EmptyResolved.INSTANCE, r,
                "non-Java file → EmptyResolved even with valid CompilationUnit");
    }

    @Test
    void resolveNullAstReturnsEmpty(@TempDir Path tmp) throws ResolutionException {
        resolver.bootstrap(tmp);
        DiscoveredFile java = new DiscoveredFile(Path.of("Foo.java"), "java", 100);
        Resolved r = resolver.resolve(java, null);
        assertSame(EmptyResolved.INSTANCE, r);
    }

    @Test
    void resolveStringAstReturnsEmpty(@TempDir Path tmp) throws ResolutionException {
        resolver.bootstrap(tmp);
        DiscoveredFile java = new DiscoveredFile(Path.of("Foo.java"), "java", 100);
        Resolved r = resolver.resolve(java, "not a CompilationUnit");
        assertSame(EmptyResolved.INSTANCE, r,
                "wrong AST type → EmptyResolved instead of ClassCastException");
    }

    @Test
    void resolveLanguageCheckIsCaseInsensitive(@TempDir Path tmp) throws ResolutionException {
        resolver.bootstrap(tmp);
        // "Java" instead of "java" — must still match.
        DiscoveredFile mixed = new DiscoveredFile(Path.of("Foo.java"), "Java", 100);
        Resolved r = resolver.resolve(mixed, parse("class Foo {}"));
        assertNotSame(EmptyResolved.INSTANCE, r);
        assertInstanceOf(JavaResolved.class, r);
    }

    // ---------- resolve() — happy path ----------

    @Test
    void resolveValidCompilationUnitReturnsJavaResolved(@TempDir Path tmp) throws ResolutionException {
        resolver.bootstrap(tmp);
        DiscoveredFile java = new DiscoveredFile(Path.of("Foo.java"), "java", 100);
        CompilationUnit cu = parse("class Foo {}");
        Resolved r = resolver.resolve(java, cu);

        assertNotSame(EmptyResolved.INSTANCE, r);
        assertInstanceOf(JavaResolved.class, r);
        assertTrue(r.isAvailable());
    }

    @Test
    void javaResolvedCarriesTheCompilationUnit(@TempDir Path tmp) throws ResolutionException {
        resolver.bootstrap(tmp);
        DiscoveredFile java = new DiscoveredFile(Path.of("Foo.java"), "java", 100);
        CompilationUnit cu = parse("class Foo {}");

        JavaResolved r = (JavaResolved) resolver.resolve(java, cu);

        assertSame(cu, r.cu());
    }

    @Test
    void javaResolvedCarriesTheSolver(@TempDir Path tmp) throws ResolutionException {
        resolver.bootstrap(tmp);
        DiscoveredFile java = new DiscoveredFile(Path.of("Foo.java"), "java", 100);
        CompilationUnit cu = parse("class Foo {}");

        JavaResolved r = (JavaResolved) resolver.resolve(java, cu);

        assertNotNull(r.solver(),
                "the resolver builds a real JavaSymbolSolver and threads it through");
    }

    // ---------- Solver smoke tests ----------

    @Test
    void solverResolvesJavaLangStringViaReflection(@TempDir Path tmp) throws ResolutionException {
        // Smoke test: ReflectionTypeSolver alone (empty project) lets us resolve
        // java.lang.String. Confirms the wiring is correct end-to-end.
        resolver.bootstrap(tmp);
        assertSolverResolvesString(resolver);
    }

    @Test
    void solverResolvesProjectClassFromSourceRoot(@TempDir Path tmp) throws Exception {
        // bootstrap with a single source root + a single file; resolve a use of
        // that class from a separate parsed file.
        Files.createDirectories(tmp.resolve("src/main/java/com/example"));
        Files.writeString(tmp.resolve("src/main/java/com/example/Foo.java"),
                "package com.example; public class Foo { public String bar() { return \"\"; } }");
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");

        resolver.bootstrap(tmp);

        assertSolverResolvesType(resolver,
                "package com.example; class Bar { Foo f; }",
                "Foo", "com.example.Foo");
    }

    @Test
    void resolveProducesDistinctJavaResolvedPerCall(@TempDir Path tmp) throws ResolutionException {
        // Two resolve() calls don't cache — each gets a fresh JavaResolved
        // record instance carrying the caller's CompilationUnit reference.
        resolver.bootstrap(tmp);
        DiscoveredFile java = new DiscoveredFile(Path.of("Foo.java"), "java", 100);
        CompilationUnit cu1 = parse("class Foo {}");
        CompilationUnit cu2 = parse("class Foo {}");

        Resolved r1 = resolver.resolve(java, cu1);
        Resolved r2 = resolver.resolve(java, cu2);

        assertNotSame(r1, r2,
                "no caching — each resolve() returns a fresh JavaResolved");
        assertSame(cu1, ((JavaResolved) r1).cu(),
                "cu1 reference is preserved through to JavaResolved.cu()");
        assertSame(cu2, ((JavaResolved) r2).cu(),
                "cu2 reference is preserved through to JavaResolved.cu()");
        assertNotSame(((JavaResolved) r1).cu(), ((JavaResolved) r2).cu(),
                "the two JavaResolved instances carry distinct CompilationUnit objects (identity, not value)");
    }

    // ---------- Helpers ----------

    private static CompilationUnit parse(String source) {
        return new JavaParser().parse(source).getResult().orElseThrow();
    }

    /** Smoke test: solver resolves java.lang.String via ReflectionTypeSolver. */
    private static void assertSolverResolvesString(JavaSymbolResolver resolver) {
        ResolvedType t = resolveTypeOf(resolver, "class Z { String s; }", "String");
        assertNotNull(t);
        assertTrue(t.describe().contains("String"),
                "solver describes the type — got " + t.describe());
    }

    /** Resolve a field's declared type by name via the resolver's solver. */
    private static void assertSolverResolvesType(JavaSymbolResolver resolver,
                                                 String source,
                                                 String fieldTypeName,
                                                 String expectedFqnFragment) {
        ResolvedType t = resolveTypeOf(resolver, source, fieldTypeName);
        assertNotNull(t);
        assertTrue(t.describe().contains(expectedFqnFragment),
                "expected '" + expectedFqnFragment + "' in resolved type, got '" + t.describe() + "'");
    }

    /** Parse the source with the resolver's solver attached and look up the named field's type. */
    private static ResolvedType resolveTypeOf(JavaSymbolResolver resolver, String source, String fieldType) {
        ParserConfiguration cfg = new ParserConfiguration().setSymbolResolver(resolver.symbolSolver());
        ParseResult<CompilationUnit> parsed = new JavaParser(cfg).parse(source);
        CompilationUnit cu = parsed.getResult().orElseThrow();

        // Find first field with the matching declared-type name.
        Optional<com.github.javaparser.ast.type.Type> fieldTypeNode = cu.findAll(
                com.github.javaparser.ast.body.FieldDeclaration.class).stream()
                .flatMap(f -> f.getVariables().stream())
                .map(v -> v.getType())
                .filter(t -> t.asString().equals(fieldType))
                .findFirst();
        assertTrue(fieldTypeNode.isPresent(),
                "test source has no field of type '" + fieldType + "'");
        return fieldTypeNode.get().resolve();
    }
}
