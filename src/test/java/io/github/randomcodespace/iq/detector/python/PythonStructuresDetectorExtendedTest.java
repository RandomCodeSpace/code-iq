package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.detector.DetectorTestUtils;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PythonStructuresDetectorExtendedTest {

    private final PythonStructuresDetector detector = new PythonStructuresDetector();

    private static String pad(String code) {
        return code + "\n" + "#\n".repeat(260_000);
    }

    // ---- Dataclass detection ----

    @Test
    void detectsDataclassDecorator() {
        String code = """
                @dataclass
                class Point:
                    x: float
                    y: float
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("shapes.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var classNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.CLASS && "Point".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertNotNull(classNode.getAnnotations());
        assertTrue(classNode.getAnnotations().contains("dataclass"));
    }

    @Test
    void detectsFrozenDataclass() {
        String code = """
                @dataclass(frozen=True)
                class ImmutablePoint:
                    x: float
                    y: float
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("shapes.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var classNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.CLASS && "ImmutablePoint".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertNotNull(classNode.getAnnotations());
        assertFalse(classNode.getAnnotations().isEmpty());
    }

    @Test
    void regexFallback_detectsDataclass() {
        String code = pad("""
                @dataclass
                class Vector:
                    dx: float
                    dy: float
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("vectors.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.CLASS && "Vector".equals(n.getLabel())),
                "regex fallback should detect @dataclass class");
    }

    // ---- TypedDict ----

    @Test
    void detectsTypedDictClass() {
        String code = """
                from typing import TypedDict

                class UserDict(TypedDict):
                    name: str
                    age: int
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("types.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.CLASS && "UserDict".equals(n.getLabel())),
                "should detect TypedDict class");
        // EXTENDS edge to TypedDict
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
        // IMPORTS edge for TypedDict
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.IMPORTS));
    }

    @Test
    void regexFallback_detectsTypedDict() {
        String code = pad("""
                class Config(TypedDict):
                    host: str
                    port: int
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("types.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.CLASS && "Config".equals(n.getLabel())),
                "regex fallback should detect TypedDict class");
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
    }

    // ---- Protocol ----

    @Test
    void detectsProtocolClass() {
        String code = """
                from typing import Protocol

                class Drawable(Protocol):
                    def draw(self) -> None:
                        pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("protocols.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.CLASS && "Drawable".equals(n.getLabel())));
        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.METHOD && "Drawable.draw".equals(n.getLabel())));
    }

    // ---- NamedTuple ----

    @Test
    void detectsNamedTupleClass() {
        String code = """
                from typing import NamedTuple

                class Coordinate(NamedTuple):
                    x: float
                    y: float
                    z: float = 0.0
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("coords.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.CLASS && "Coordinate".equals(n.getLabel())));
        assertTrue(result.edges().stream().anyMatch(e -> e.getKind() == EdgeKind.EXTENDS));
    }

    // ---- Return type annotations ----

    @Test
    void detectsFunctionWithReturnAnnotation() {
        String code = """
                def get_name() -> str:
                    return "hello"

                def compute(x: int, y: int) -> int:
                    return x + y
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("utils.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(2, result.nodes().size());
        assertTrue(result.nodes().stream().allMatch(n -> n.getKind() == NodeKind.METHOD));
        assertTrue(result.nodes().stream().anyMatch(n -> "get_name".equals(n.getLabel())));
        assertTrue(result.nodes().stream().anyMatch(n -> "compute".equals(n.getLabel())));
    }

    @Test
    void regexFallback_detectsFunctionWithAnnotation() {
        String code = pad("""
                def fetch_items() -> list:
                    return []
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("utils.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.METHOD && "fetch_items".equals(n.getLabel())));
    }

    // ---- Async functions ----

    @Test
    void detectsAsyncTopLevelFunction() {
        String code = """
                async def connect_db():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("db.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.METHOD, result.nodes().get(0).getKind());
        assertEquals("connect_db", result.nodes().get(0).getLabel());
        assertEquals(true, result.nodes().get(0).getProperties().get("async"));
    }

    @Test
    void regexFallback_detectsAsyncFunction() {
        String code = pad("""
                async def handle_request(request):
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("handlers.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertTrue(result.nodes().stream()
                .anyMatch(n -> n.getKind() == NodeKind.METHOD && "handle_request".equals(n.getLabel())));
        var node = result.nodes().stream()
                .filter(n -> "handle_request".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertEquals(true, node.getProperties().get("async"));
    }

    // ---- Nested classes ----

    @Test
    void detectsNestedClass() {
        String code = """
                class Outer:
                    class Inner:
                        def method(self):
                            pass
                    def outer_method(self):
                        pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("nested.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        // Should detect Outer (class), Inner (class), and at least outer_method (method)
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS && "Outer".equals(n.getLabel())));
        assertTrue(result.nodes().stream().anyMatch(n -> n.getKind() == NodeKind.CLASS && "Inner".equals(n.getLabel())));
    }

    // ---- Imports from multiple modules ----

    @Test
    void detectsMultipleFromImports() {
        String code = """
                from os.path import join, exists, dirname
                from typing import List, Dict, Optional
                from collections import defaultdict
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("imports.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        // 3 from-import statements = 3 IMPORTS edges
        assertEquals(3, result.edges().size());
        assertTrue(result.edges().stream().allMatch(e -> e.getKind() == EdgeKind.IMPORTS));
    }

    @Test
    void detectsPlainImports() {
        String code = """
                import os
                import sys
                import json
                import re
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("imports.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(4, result.edges().size());
        assertTrue(result.edges().stream().allMatch(e -> e.getKind() == EdgeKind.IMPORTS));
    }

    @Test
    void regexFallback_detectsImports() {
        String code = pad("""
                import os
                import sys
                from pathlib import Path
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("helpers.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long importEdges = result.edges().stream().filter(e -> e.getKind() == EdgeKind.IMPORTS).count();
        assertTrue(importEdges >= 3, "regex fallback should detect import edges");
    }

    // ---- Very short file edge case ----

    @Test
    void veryShortFile_singleClass() {
        String code = "class A:\n    pass\n";
        DetectorContext ctx = DetectorTestUtils.contextFor("a.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.CLASS, result.nodes().get(0).getKind());
        assertEquals("A", result.nodes().get(0).getLabel());
    }

    @Test
    void veryShortFile_singleFunction() {
        String code = "def f():\n    pass\n";
        DetectorContext ctx = DetectorTestUtils.contextFor("f.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(1, result.nodes().size());
        assertEquals(NodeKind.METHOD, result.nodes().get(0).getKind());
    }

    @Test
    void singleLineComment_noResults() {
        String code = "# just a comment\n";
        DetectorContext ctx = DetectorTestUtils.contextFor("comment.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        assertEquals(0, result.nodes().size());
        assertEquals(0, result.edges().size());
    }

    // ---- Multiple decorators on same function ----

    @Test
    void detectsFunctionWithMultipleDecorators() {
        String code = """
                @staticmethod
                @some_other_decorator
                def helper():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("helpers.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var node = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.METHOD && "helper".equals(n.getLabel()))
                .findFirst().orElseThrow();
        assertNotNull(node.getAnnotations());
        assertTrue(node.getAnnotations().size() >= 1);
    }

    // ---- Class with multiple methods ----

    @Test
    void classWithMultipleMethodsGeneratesMultipleDefinesEdges() {
        String code = """
                class Calculator:
                    def add(self, a, b):
                        return a + b
                    def subtract(self, a, b):
                        return a - b
                    def multiply(self, a, b):
                        return a * b
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("calc.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long definesEdges = result.edges().stream()
                .filter(e -> e.getKind() == EdgeKind.DEFINES).count();
        assertEquals(3, definesEdges);

        long methodNodes = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.METHOD).count();
        assertEquals(3, methodNodes);
    }

    @Test
    void regexFallback_classWithMultipleMethods() {
        String code = pad("""
                class Service:
                    def create(self):
                        pass
                    def read(self):
                        pass
                    def update(self):
                        pass
                    def delete(self):
                        pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("service.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        long methodNodes = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.METHOD).count();
        assertTrue(methodNodes >= 4, "regex fallback should detect all methods");
    }

    // ---- Module __all__ with single export ----

    @Test
    void singleExportInAll() {
        String code = """
                __all__ = ['only_this']

                def only_this():
                    pass

                def internal():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("module.py", "python", code);
        DetectorResult result = detector.detect(ctx);

        var moduleNode = result.nodes().stream()
                .filter(n -> n.getKind() == NodeKind.MODULE).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        List<String> exports = (List<String>) moduleNode.getProperties().get("__all__");
        assertEquals(1, exports.size());
        assertTrue(exports.contains("only_this"));

        var onlyThis = result.nodes().stream()
                .filter(n -> "only_this".equals(n.getLabel())).findFirst().orElseThrow();
        assertEquals(true, onlyThis.getProperties().get("exported"));

        var internal = result.nodes().stream()
                .filter(n -> "internal".equals(n.getLabel())).findFirst().orElseThrow();
        assertNull(internal.getProperties().get("exported"));
    }

    // ---- Determinism on complex code ----

    @Test
    void deterministicOnComplexCode() {
        String code = """
                from typing import List, Optional
                import os

                __all__ = ['MyClass', 'utility']

                @dataclass
                class MyClass(BaseClass):
                    x: int
                    y: str

                    async def async_method(self):
                        pass

                    def sync_method(self):
                        pass

                async def utility() -> None:
                    pass

                def _private():
                    pass
                """;
        DetectorContext ctx = DetectorTestUtils.contextFor("complex.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }

    @Test
    void regexFallback_deterministicOnComplexCode() {
        String code = pad("""
                from typing import List
                import os

                class A(B):
                    def m(self):
                        pass

                def f():
                    pass
                """);
        DetectorContext ctx = DetectorTestUtils.contextFor("complex.py", "python", code);
        DetectorTestUtils.assertDeterministic(detector, ctx);
    }
}
