"""Tests for CSharpStructuresDetector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.csharp.csharp_structures import CSharpStructuresDetector
from code_intelligence.models.graph import EdgeKind, NodeKind


def _ctx(content, path="MyService.cs"):
    return DetectorContext(
        file_path=path,
        language="csharp",
        content=content.encode(),
    )


class TestCSharpStructuresDetector:
    def setup_method(self):
        self.detector = CSharpStructuresDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "csharp_structures"
        assert self.detector.supported_languages == ("csharp",)

    def test_detects_class_interface_enum_namespace(self):
        csharp_src = """\
using System;
using System.Collections.Generic;

namespace MyApp.Services
{
    public interface IUserService
    {
        void GetUser(int id);
    }

    public class UserService : IUserService
    {
        public void GetUser(int id)
        {
            Console.WriteLine(id);
        }
    }

    public enum UserRole
    {
        Admin,
        User
    }
}
"""
        ctx = _ctx(csharp_src)
        r = self.detector.detect(ctx)
        # Namespace MODULE
        modules = [n for n in r.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        assert modules[0].label == "MyApp.Services"
        # Interface
        ifaces = [n for n in r.nodes if n.kind == NodeKind.INTERFACE]
        assert len(ifaces) == 1
        assert ifaces[0].label == "IUserService"
        # Class
        classes = [n for n in r.nodes if n.kind == NodeKind.CLASS]
        assert len(classes) == 1
        assert classes[0].label == "UserService"
        # Enum
        enums = [n for n in r.nodes if n.kind == NodeKind.ENUM]
        assert len(enums) == 1
        assert enums[0].label == "UserRole"
        # IMPLEMENTS edge
        impl_edges = [e for e in r.edges if e.kind == EdgeKind.IMPLEMENTS]
        assert len(impl_edges) == 1
        assert "IUserService" in impl_edges[0].target
        # IMPORTS edges (using statements)
        import_edges = [e for e in r.edges if e.kind == EdgeKind.IMPORTS]
        import_targets = {e.target for e in import_edges}
        assert "System" in import_targets
        assert "System.Collections.Generic" in import_targets

    def test_irrelevant_content_returns_empty(self):
        ctx = _ctx("// just a comment in a C# file\n")
        r = self.detector.detect(ctx)
        assert r.nodes == []
        assert r.edges == []

    def test_determinism(self):
        csharp_src = "namespace Test\n{\n    public class Foo {}\n}\n"
        ctx = _ctx(csharp_src)
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]

    def test_returns_detector_result(self):
        ctx = _ctx("")
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)
