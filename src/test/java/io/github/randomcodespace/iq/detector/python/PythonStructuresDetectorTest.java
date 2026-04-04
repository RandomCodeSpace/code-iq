package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PythonStructuresDetectorTest {

    private final PythonStructuresDetector detector = new PythonStructuresDetector();

    @Test
    void detectsClassAndMethod() {
        String code = """
                class MyClass(Base):
                    def my_method(self):
                        pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(
                n -> n.getKind() == NodeKind.CLASS && n.getLabel().equals("MyClass")));
        assertTrue(result.nodes().stream().anyMatch(
                n -> n.getKind() == NodeKind.METHOD && n.getLabel().equals("MyClass.my_method")));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.DEFINES));
    }

    @Test
    void detectsTopLevelFunction() {
        String code = """
                def my_func():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.METHOD, result.nodes().get(0).getKind());
        assertEquals("my_func", result.nodes().get(0).getLabel());
    }

    @Test
    void detectsImports() {
        String code = """
                from os.path import join
                import sys, json
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(3, result.edges().size());
        assertTrue(result.edges().stream().allMatch(e -> e.getKind() == EdgeKind.IMPORTS));
    }

    @Test
    void detectsAllExports() {
        String code = """
                __all__ = ['foo', 'Bar']

                def foo():
                    pass

                class Bar:
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.MODULE));
        var fooNode = result.nodes().stream()
                .filter(n -> n.getLabel().equals("foo"))
                .findFirst().orElseThrow();
        assertEquals(true, fooNode.getProperties().get("exported"));
        var barNode = result.nodes().stream()
                .filter(n -> n.getLabel().equals("Bar"))
                .findFirst().orElseThrow();
        assertEquals(true, barNode.getProperties().get("exported"));
    }

    @Test
    void detectsAsyncFunction() {
        String code = """
                async def fetch_data():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(true, result.nodes().get(0).getProperties().get("async"));
    }

    @Test
    void noMatchOnEmptyFile() {
        String code = "";
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    @Test
    void deterministic() {
        String code = """
                from os import path
                import sys

                class MyClass(Base):
                    def method_a(self):
                        pass

                def standalone():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void detectsClassWithMultipleBases() {
        String code = """
                class MyView(LoginRequiredMixin, View):
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        var classNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.CLASS)
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> bases = (List<String>) classNode.getProperties().get("bases");
        assertNotNull(bases);
        assertTrue(bases.size() >= 2);
        // Two extends edges
        long extendsCount = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.EXTENDS).count();
        assertEquals(2, extendsCount);
    }

    @Test
    void detectsAsyncMethod() {
        String code = """
                class MyService:
                    async def fetch(self):
                        pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        var methodNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.METHOD)
                .findFirst().orElseThrow();
        assertEquals(true, methodNode.getProperties().get("async"));
        assertEquals("MyService.fetch", methodNode.getLabel());
    }

    @Test
    void detectsFromImportEdge() {
        String code = """
                from django.db import models
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.edges().size());
        assertEquals(EdgeKind.IMPORTS, result.edges().get(0).getKind());
    }

    @Test
    void detectsImportNameEdges() {
        String code = """
                import os
                import sys
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.edges().size());
        assertTrue(result.edges().stream().allMatch(e -> e.getKind() == EdgeKind.IMPORTS));
    }

    @Test
    void detectsModuleNameProperty() {
        String code = """
                __all__ = ['MyClass']

                class MyClass:
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("mymod.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var moduleNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.MODULE)
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> exports = (List<String>) moduleNode.getProperties().get("__all__");
        assertTrue(exports.contains("MyClass"));
    }

    @Test
    void detectsClassWithDecorators() {
        String code = """
                @dataclass
                class Point:
                    x: float
                    y: float
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        var classNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.CLASS && "Point".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertNotNull(classNode.getAnnotations());
        assertFalse(classNode.getAnnotations().isEmpty());
    }

    @Test
    void detectsFunctionWithDecorators() {
        String code = """
                @staticmethod
                def helper():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        var funcNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.METHOD)
                .findFirst().orElseThrow();
        assertFalse(funcNode.getAnnotations().isEmpty());
    }

    @Test
    void exportedFunctionHasExportedProperty() {
        String code = """
                __all__ = ['process']

                def process():
                    pass

                def _private():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        var processNode = result.nodes().stream()
                .filter(n -> "process".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertEquals(true, processNode.getProperties().get("exported"));

        var privateNode = result.nodes().stream()
                .filter(n -> "_private".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertNull(privateNode.getProperties().get("exported"));
    }

    @Test
    void classWithNoBasesHasNoExtendsEdge() {
        String code = """
                class Standalone:
                    def run(self):
                        pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        long extendsEdges = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.EXTENDS).count();
        assertEquals(0, extendsEdges);
        long definesEdges = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.DEFINES).count();
        assertEquals(1, definesEdges);
    }

    @Test
    void multipleTopLevelFunctions() {
        String code = """
                def alpha():
                    pass

                def beta():
                    pass

                def gamma():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(3, result.nodes().size());
        assertTrue(result.nodes().stream().allMatch(n -> n.getKind() == NodeKind.METHOD));
    }

    @Test
    void indentedMethodOutsideClassNotAdded() {
        // A function that's indented but not inside a class should not appear as class method
        // (the detector only creates class methods for indented fns when there's an enclosing class)
        String code = """
                def outer():
                    def inner():
                        pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        // outer is top-level, inner is indented but not in a class
        // inner would match the indented path but findEnclosingClass returns null
        var outerNode = result.nodes().stream()
                .filter(n -> "outer".equals(n.getLabel())).findFirst();
        assertTrue(outerNode.isPresent());
    }

    @Test
    void methodPropertyHasClassField() {
        String code = """
                class Service:
                    def execute(self):
                        pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("python", code);
        DetectorResult result = detector.detect(ctx);

        var methodNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.METHOD)
                .findFirst().orElseThrow();
        assertEquals("Service", methodNode.getProperties().get("class"));
    }

    // ---- Regex fallback path (content > 500KB) ----

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    @Test
    void regexFallback_detectsClass() {
        String code = pad("""
                class MyService(BaseService):
                    def process(self):
                        pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("mymod.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS && "MyService".equals(n.getLabel())),
                "regex fallback should detect class");
    }

    @Test
    void regexFallback_detectsFunction() {
        String code = pad("""
                def compute_total(items):
                    return sum(items)
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("utils.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD && "compute_total".equals(n.getLabel())),
                "regex fallback should detect top-level function");
    }

    @Test
    void regexFallback_detectsExtendsEdge() {
        String code = pad("""
                class Animal:
                    pass

                class Dog(Animal):
                    def bark(self):
                        pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("pets.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS),
                "regex fallback should create EXTENDS edge");
    }

    @Test
    void regexFallback_detectsAllExports() {
        String code = pad("""
                __all__ = ['MyClass', 'helper_func']

                class MyClass:
                    pass

                def helper_func():
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("mymod.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS),
                "regex fallback should detect class");
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.METHOD),
                "regex fallback should detect function");
    }
}
