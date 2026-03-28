"""Tests for GoStructuresDetector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.go.go_structures import GoStructuresDetector
from code_intelligence.models.graph import EdgeKind, NodeKind


def _ctx(content, path="main.go"):
    return DetectorContext(
        file_path=path,
        language="go",
        content=content.encode(),
    )


class TestGoStructuresDetector:
    def setup_method(self):
        self.detector = GoStructuresDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "go_structures"
        assert self.detector.supported_languages == ("go",)

    def test_detects_structs_interfaces_methods_functions(self):
        go_src = '''\
package server

import (
    "fmt"
    "net/http"
)

type Handler struct {
    Name string
}

type Router interface {
    Route(path string)
}

func (h *Handler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
    fmt.Fprintf(w, "Hello")
}

func NewHandler() *Handler {
    return &Handler{}
}
'''
        ctx = _ctx(go_src)
        r = self.detector.detect(ctx)
        # Package MODULE node
        modules = [n for n in r.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        assert modules[0].label == "server"
        # Struct CLASS node
        classes = [n for n in r.nodes if n.kind == NodeKind.CLASS]
        assert len(classes) == 1
        assert classes[0].label == "Handler"
        assert classes[0].properties["exported"] is True
        # Interface node
        ifaces = [n for n in r.nodes if n.kind == NodeKind.INTERFACE]
        assert len(ifaces) == 1
        assert ifaces[0].label == "Router"
        # Method nodes (receiver method + package-level function)
        methods = [n for n in r.nodes if n.kind == NodeKind.METHOD]
        method_labels = {n.label for n in methods}
        assert "Handler.ServeHTTP" in method_labels
        assert "NewHandler" in method_labels
        # Imports
        import_edges = [e for e in r.edges if e.kind == EdgeKind.IMPORTS]
        import_targets = {e.target for e in import_edges}
        assert "fmt" in import_targets
        assert "net/http" in import_targets
        # DEFINES edge from struct to method
        defines_edges = [e for e in r.edges if e.kind == EdgeKind.DEFINES]
        assert len(defines_edges) == 1

    def test_irrelevant_content_returns_empty(self):
        ctx = _ctx("// just a comment\n", path="notes.txt")
        r = self.detector.detect(ctx)
        assert r.nodes == []
        assert r.edges == []

    def test_determinism(self):
        go_src = "package main\n\ntype Foo struct {\n    X int\n}\n"
        ctx = _ctx(go_src)
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]

    def test_returns_detector_result(self):
        ctx = _ctx("")
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)
