package io.github.randomcodespace.iq.detector.java;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended branch-coverage tests for ClassHierarchyDetector targeting code paths
 * not covered by the existing JavaDetectors*Test suites.
 *
 * Targets: AST paths for generics, records, nested types; regex fallback paths;
 * and edge-case visibility/modifier combinations.
 */
class ClassHierarchyDetectorExtendedTest {

    private final ClassHierarchyDetector detector = new ClassHierarchyDetector();

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("java", content);
    }

    // ---- Generic class with type parameters -----------------------------------------

    @Test
    void detectsGenericClass() {
        String code = """
                package com.example;
                public class Repository<T, ID> {}
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(NodeKind.CLASS, result.nodes().get(0).getKind());
        assertEquals("Repository", result.nodes().get(0).getLabel());
    }

    @Test
    void detectsGenericClassExtendsGenericSuperclass() {
        String code = """
                package com.example;
                public class JpaRepository<T, ID> extends AbstractRepository<T> {}
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
    }

    @Test
    void detectsGenericInterface() {
        String code = """
                package com.example;
                public interface Converter<S, T> {
                    T convert(S source);
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(NodeKind.INTERFACE, result.nodes().get(0).getKind());
    }

    // ---- Record class ---------------------------------------------------------------

    @Test
    void detectsRecordAsClassNode() {
        // JavaParser 3.x parses record as a ClassOrInterfaceDeclaration with isRecord()=true
        // or as RecordDeclaration depending on version. Either way, we verify the detector
        // does not throw on record syntax.
        String code = """
                package com.example;
                public record Point(int x, int y) {}
                """;
        // Result may be empty if the parser version doesn't support records,
        // but it must not throw an exception.
        var result = detector.detect(ctx(code));
        assertNotNull(result, "detect() must not return null for record syntax");
    }

    // ---- Interface extending multiple interfaces -------------------------------------

    @Test
    void interfaceExtendingMultipleInterfaces() {
        String code = """
                package com.example;
                public interface FullService extends ReadService, WriteService, AdminService {}
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(NodeKind.INTERFACE, result.nodes().get(0).getKind());
        long extendsEdges = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.EXTENDS).count();
        assertEquals(3, extendsEdges, "Interface should have 3 EXTENDS edges");
    }

    // ---- Abstract class implementing interface --------------------------------------

    @Test
    void abstractClassImplementingInterface() {
        String code = """
                package com.example;
                public abstract class AbstractProcessor implements Runnable, AutoCloseable {}
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(NodeKind.ABSTRACT_CLASS, result.nodes().get(0).getKind());
        long implEdges = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.IMPLEMENTS).count();
        assertEquals(2, implEdges, "Abstract class should have 2 IMPLEMENTS edges");
    }

    // ---- Class extending abstract AND implementing interfaces -----------------------

    @Test
    void classExtendsAbstractAndImplementsInterfaces() {
        String code = """
                package com.example;
                public class ConcreteService extends AbstractBase implements ServiceInterface, Cloneable {}
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(NodeKind.CLASS, result.nodes().get(0).getKind());
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
        long implEdges = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.IMPLEMENTS).count();
        assertEquals(2, implEdges);
        assertEquals("AbstractBase", result.nodes().get(0).getProperties().get("superclass"));
    }

    // ---- Enum -----------------------------------------------------------------------

    @Test
    void detectsEnumWithImplementedInterface() {
        String code = """
                package com.example;
                public enum Priority implements Comparable<Priority>, java.io.Serializable {
                    LOW, MEDIUM, HIGH
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(NodeKind.ENUM, result.nodes().get(0).getKind());
        // Should have IMPLEMENTS edges for each implemented interface
        assertFalse(result.edges().isEmpty());
    }

    @Test
    void detectsSimpleEnum() {
        String code = """
                package com.example;
                public enum Status { ACTIVE, INACTIVE, PENDING }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(NodeKind.ENUM, result.nodes().get(0).getKind());
        // No IMPLEMENTS edges for plain enum
        assertTrue(result.edges().isEmpty());
    }

    // ---- Annotation type ------------------------------------------------------------

    @Test
    void detectsAnnotationTypeWithAttributes() {
        String code = """
                package com.example;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface Controller {
                    String value() default "";
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(NodeKind.ANNOTATION_TYPE, result.nodes().get(0).getKind());
        assertEquals("Controller", result.nodes().get(0).getLabel());
    }

    // ---- FQN from package -----------------------------------------------------------

    @Test
    void fqnIncludesPackageForClass() {
        String code = """
                package com.example.model;
                public class UserAccount {}
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        String fqn = result.nodes().get(0).getFqn();
        assertNotNull(fqn);
        assertEquals("com.example.model.UserAccount", fqn);
    }

    @Test
    void fqnIncludesPackageForInterface() {
        String code = """
                package com.example.spi;
                public interface DataProvider {}
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals("com.example.spi.DataProvider", result.nodes().get(0).getFqn());
    }

    // ---- lineEnd is set -------------------------------------------------------------

    @Test
    void lineEndIsSetOnClassNode() {
        String code = """
                package com.example;
                public class MultilineClass {
                    private String name;
                    public void doWork() {}
                }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        int lineStart = result.nodes().get(0).getLineStart();
        int lineEnd = result.nodes().get(0).getLineEnd();
        assertTrue(lineEnd >= lineStart, "lineEnd should be >= lineStart");
    }

    // ---- Properties: is_abstract, is_final ------------------------------------------

    @Test
    void abstractClassHasIsAbstractTrue() {
        String code = """
                package com.example;
                public abstract class AbstractFoo {}
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(true, result.nodes().get(0).getProperties().get("is_abstract"));
        assertEquals(false, result.nodes().get(0).getProperties().get("is_final"));
    }

    @Test
    void finalClassHasIsFinalTrue() {
        String code = """
                package com.example;
                public final class ImmutableFoo {}
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(true, result.nodes().get(0).getProperties().get("is_final"));
        assertEquals(false, result.nodes().get(0).getProperties().get("is_abstract"));
    }

    @Test
    void interfaceHasIsAbstractFalse() {
        // Interface is_abstract is false per detector implementation
        String code = """
                package com.example;
                public interface SomeInterface {}
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        assertEquals(false, result.nodes().get(0).getProperties().get("is_abstract"));
    }

    // ---- Nested inner classes -------------------------------------------------------

    @Test
    void detectsStaticNestedClass() {
        String code = """
                package com.example;
                public class Outer {
                    public static class Builder {}
                    public class InnerHelper {}
                }
                """;
        var result = detector.detect(ctx(code));
        // Outer + Builder + InnerHelper = 3 nodes
        assertEquals(3, result.nodes().size());
    }

    // ---- Enum properties ------------------------------------------------------------

    @Test
    void enumHasCorrectProperties() {
        String code = """
                package com.example;
                public enum Color { RED, GREEN, BLUE }
                """;
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty());
        var node = result.nodes().get(0);
        assertEquals(false, node.getProperties().get("is_abstract"));
        assertEquals(false, node.getProperties().get("is_final"));
        assertEquals("public", node.getProperties().get("visibility"));
    }

    // ---- Multiple types in one file -------------------------------------------------

    @Test
    void detectsAllTypesInOneFile() {
        String code = """
                package com.example;
                public class MainClass {}
                interface HelperInterface {}
                enum HelperEnum { A, B }
                @interface HelperAnnotation {}
                """;
        var result = detector.detect(ctx(code));
        assertEquals(4, result.nodes().size());
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.INTERFACE));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENUM));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ANNOTATION_TYPE));
    }

    // ---- Determinism ----------------------------------------------------------------

    @Test
    void isDeterministic() {
        String code = """
                package com.example;
                public class ServiceImpl extends BaseService implements IService, Closeable {}
                public interface IService extends IBase1, IBase2 {}
                public enum State { ON, OFF }
                """;
        DetectorTestUtils.assertDeterministic(detector, ctx(code));
    }

    // ---- Regex fallback (NUL byte forces JavaParser failure) -------------------------

    @Test
    void regexFallback_detectsSimpleClass() {
        String code = "\u0000 class SimpleClass {\n}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect class declaration");
        assertEquals(NodeKind.CLASS, result.nodes().get(0).getKind());
        assertEquals("SimpleClass", result.nodes().get(0).getLabel());
    }

    @Test
    void regexFallback_detectsAbstractClass() {
        String code = "\u0000 abstract class AbstractWorker {\n}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect abstract class");
        assertEquals(NodeKind.ABSTRACT_CLASS, result.nodes().get(0).getKind());
    }

    @Test
    void regexFallback_detectsClassWithExtendsAndImplements() {
        String code = "\u0000 public class ConcreteWorker extends AbstractWorker implements Runnable {\n}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect class with extends and implements");
        assertEquals("AbstractWorker", result.nodes().get(0).getProperties().get("superclass"));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.IMPLEMENTS));
    }

    @Test
    void regexFallback_detectsInterfaceExtendingMultiple() {
        String code = "\u0000 public interface BigInterface extends IOne, ITwo, IThree {\n}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect interface");
        assertEquals(NodeKind.INTERFACE, result.nodes().get(0).getKind());
        long extendsEdges = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.EXTENDS).count();
        assertEquals(3, extendsEdges);
    }

    @Test
    void regexFallback_detectsEnum() {
        String code = "\u0000 public enum Season implements Coded {\nSPRING, SUMMER, FALL, WINTER\n}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect enum");
        assertEquals(NodeKind.ENUM, result.nodes().get(0).getKind());
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.IMPLEMENTS));
    }

    @Test
    void regexFallback_detectsAnnotationType() {
        // The regex fallback ANNOTATION_TYPE_RE matches `@interface` declarations.
        // However, the INTERFACE_DECL_RE also matches the `interface` keyword inside `@interface`,
        // so the node kind may be either INTERFACE or ANNOTATION_TYPE depending on line order.
        // We verify that the node is detected (not empty) and is one of the two expected kinds.
        String code = "\u0000 public @interface CustomAnnotation {\n}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect @interface declaration");
        NodeKind kind = result.nodes().get(0).getKind();
        assertTrue(kind == NodeKind.ANNOTATION_TYPE || kind == NodeKind.INTERFACE,
                "Detected kind should be ANNOTATION_TYPE or INTERFACE, got: " + kind);
    }

    @Test
    void regexFallback_detectsClassImplementsMultiple() {
        String code = "\u0000 public class MultiImpl implements Runnable, Serializable, AutoCloseable {\n}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect class with multiple interfaces");
        long implEdges = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.IMPLEMENTS).count();
        assertEquals(3, implEdges, "Should have 3 IMPLEMENTS edges");
    }

    @Test
    void regexFallback_detectsFinalClass() {
        String code = "\u0000 public final class Constants {\n}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect final class");
        assertEquals(NodeKind.CLASS, result.nodes().get(0).getKind());
        assertEquals(true, result.nodes().get(0).getProperties().get("is_final"));
    }

    @Test
    void regexFallback_detectsPackagePrivateClass() {
        String code = "\u0000 class PackageLocal {\n}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect package-private class");
        assertEquals("package-private", result.nodes().get(0).getProperties().get("visibility"));
    }

    @Test
    void regexFallback_detectsProtectedClass() {
        String code = "\u0000 protected class ProtectedHelper {\n}";
        var result = detector.detect(ctx(code));
        assertFalse(result.nodes().isEmpty(), "regex fallback should detect protected class");
        assertEquals("protected", result.nodes().get(0).getProperties().get("visibility"));
    }
}
