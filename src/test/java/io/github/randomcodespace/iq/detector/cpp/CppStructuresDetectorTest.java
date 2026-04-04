package io.github.randomcodespace.iq.detector.cpp;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CppStructuresDetectorTest {

    private final CppStructuresDetector d = new CppStructuresDetector();

    @Test
    void detectsClass() {
        String code = "#include <string>\nclass User {\npublic:\n    std::string name;\n};\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS && "User".equals(n.getLabel())));
    }

    @Test
    void detectsClassWithBaseClass() {
        String code = "class AdminUser : public User {\n};\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
    }

    @Test
    void detectsStruct() {
        String code = "struct Point {\n    int x;\n    int y;\n};\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS && "Point".equals(n.getLabel())));
    }

    @Test
    void detectsStructWithBase() {
        String code = "struct ColoredPoint : public Point {\n    int color;\n};\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
    }

    @Test
    void detectsNamespace() {
        String code = "namespace app {\n    class Service {};\n}\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE && "app".equals(n.getLabel())));
    }

    @Test
    void detectsEnum() {
        String code = "enum Status {\n    ACTIVE,\n    INACTIVE\n};\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENUM));
    }

    @Test
    void detectsEnumClass() {
        String code = "enum class Color {\n    RED,\n    GREEN,\n    BLUE\n};\n";
        DetectorResult r = d.detect(ctx(code));
        assertTrue(r.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.ENUM));
    }

    @Test
    void detectsInclude() {
        String code = "#include <vector>\n#include <string>\n#include \"myheader.hpp\"\n";
        DetectorResult r = d.detect(ctx(code));
        assertEquals(3, r.edges().stream().filter(e -> e.getKind() == EdgeKind.IMPORTS).count());
    }

    @Test
    void detectsClassAndNamespace() {
        DetectorResult r = d.detect(ctx("#include <string>\nnamespace app {\nclass User : public Entity {\n};\n}"));
        assertTrue(r.nodes().size() >= 2);
    }

    @Test
    void emptyContentReturnsEmpty() {
        DetectorResult r = d.detect(ctx(""));
        assertTrue(r.nodes().isEmpty());
        assertTrue(r.edges().isEmpty());
    }

    @Test
    void nullContentReturnsEmpty() {
        DetectorContext ctxNull = new DetectorContext("test.cpp", "cpp", null);
        DetectorResult r = d.detect(ctxNull);
        assertTrue(r.nodes().isEmpty());
    }

    @Test
    void returnsCorrectName() {
        assertEquals("cpp_structures", d.getName());
    }

    @Test
    void supportedLanguagesContainsCpp() {
        assertTrue(d.getSupportedLanguages().contains("cpp"));
    }

    @Test
    void deterministic() {
        String code = """
                #include <string>
                #include <vector>
                namespace myapp {
                class Vehicle {
                public:
                    std::string make;
                };
                class Car : public Vehicle {
                };
                struct Point {
                    int x;
                    int y;
                };
                enum class Color { RED, GREEN, BLUE };
                }
                """;
        DetectorTestUtils.assertDeterministic(d, ctx(code));
    }

    private static DetectorContext ctx(String content) {
        return DetectorTestUtils.contextFor("cpp", content);
    }
}
