"""Tests for PythonStructuresDetector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.python.python_structures import PythonStructuresDetector
from code_intelligence.models.graph import EdgeKind, NodeKind


def _ctx(content, path="app/service.py"):
    return DetectorContext(
        file_path=path,
        language="python",
        content=content.encode(),
    )


class TestPythonStructuresDetector:
    def setup_method(self):
        self.detector = PythonStructuresDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "python_structures"
        assert self.detector.supported_languages == ("python",)

    def test_detects_classes(self):
        src = '''\
class Animal:
    pass

class Dog(Animal):
    pass

@dataclass
class Config(BaseModel, Serializable):
    name: str
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        classes = [n for n in r.nodes if n.kind == NodeKind.CLASS]
        assert len(classes) == 3
        labels = {n.label for n in classes}
        assert labels == {"Animal", "Dog", "Config"}

        # Dog extends Animal
        extends_edges = [e for e in r.edges if e.kind == EdgeKind.EXTENDS]
        extend_targets = {e.target for e in extends_edges}
        assert "Animal" in extend_targets
        assert "BaseModel" in extend_targets
        assert "Serializable" in extend_targets

        # Config has bases property
        config_node = next(n for n in classes if n.label == "Config")
        assert "BaseModel" in config_node.properties["bases"]
        assert "Serializable" in config_node.properties["bases"]

        # Config has @dataclass annotation
        assert "dataclass" in config_node.annotations

    def test_detects_functions(self):
        src = '''\
def sync_handler():
    pass

async def async_handler():
    pass

def another():
    pass
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        methods = [n for n in r.nodes if n.kind == NodeKind.METHOD]
        assert len(methods) == 3
        labels = {n.label for n in methods}
        assert labels == {"sync_handler", "async_handler", "another"}

        # async detection
        async_node = next(n for n in methods if n.label == "async_handler")
        assert async_node.properties.get("async") is True

        sync_node = next(n for n in methods if n.label == "sync_handler")
        assert "async" not in sync_node.properties

    def test_detects_class_methods(self):
        src = '''\
class MyService:
    def __init__(self):
        pass

    async def process(self):
        pass

    @staticmethod
    def helper():
        pass
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        methods = [n for n in r.nodes if n.kind == NodeKind.METHOD]
        assert len(methods) == 3
        method_labels = {n.label for n in methods}
        assert "MyService.__init__" in method_labels
        assert "MyService.process" in method_labels
        assert "MyService.helper" in method_labels

        # Class property set on methods
        for m in methods:
            assert m.properties["class"] == "MyService"

        # process is async
        process_node = next(n for n in methods if n.label == "MyService.process")
        assert process_node.properties.get("async") is True

        # helper has @staticmethod annotation
        helper_node = next(n for n in methods if n.label == "MyService.helper")
        assert "staticmethod" in helper_node.annotations

        # DEFINES edges from class to methods
        defines_edges = [e for e in r.edges if e.kind == EdgeKind.DEFINES]
        assert len(defines_edges) == 3
        for edge in defines_edges:
            assert edge.source == f"py:app/service.py:class:MyService"

        # ID format for class methods
        init_node = next(n for n in methods if n.label == "MyService.__init__")
        assert init_node.id == "py:app/service.py:class:MyService:method:__init__"

    def test_detects_imports(self):
        src = '''\
import os
import sys, json
from pathlib import Path
from collections import OrderedDict, defaultdict
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        import_edges = [e for e in r.edges if e.kind == EdgeKind.IMPORTS]
        targets = {e.target for e in import_edges}
        assert "os" in targets
        assert "sys" in targets
        assert "json" in targets
        assert "pathlib" in targets
        assert "collections" in targets

    def test_detects_decorators(self):
        src = '''\
@app.route("/api")
@login_required
def my_view():
    pass
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)
        methods = [n for n in r.nodes if n.kind == NodeKind.METHOD]
        assert len(methods) == 1
        assert "app.route" in methods[0].annotations
        assert "login_required" in methods[0].annotations

    def test_detects_all_exports(self):
        src = '''\
__all__ = [
    "PublicClass",
    "public_func",
]

class PublicClass:
    pass

def public_func():
    pass

def _private():
    pass
'''
        ctx = _ctx(src)
        r = self.detector.detect(ctx)

        # Module node with __all__
        module_nodes = [n for n in r.nodes if n.kind == NodeKind.MODULE]
        assert len(module_nodes) == 1
        assert module_nodes[0].properties["__all__"] == ["PublicClass", "public_func"]

        # Exported properties
        pub_class = next(n for n in r.nodes if n.label == "PublicClass")
        assert pub_class.properties.get("exported") is True

        pub_func = next(n for n in r.nodes if n.label == "public_func")
        assert pub_func.properties.get("exported") is True

        priv_func = next(n for n in r.nodes if n.label == "_private")
        assert "exported" not in priv_func.properties

    def test_empty_returns_empty(self):
        ctx = _ctx("")
        r = self.detector.detect(ctx)
        assert r.nodes == []
        assert r.edges == []

    def test_comments_only_returns_empty(self):
        ctx = _ctx("# just a comment\n# nothing here\n")
        r = self.detector.detect(ctx)
        assert r.nodes == []
        assert r.edges == []

    def test_determinism(self):
        src = '''\
class Foo:
    pass

def bar():
    pass
'''
        ctx = _ctx(src)
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]
        assert len(r1.edges) == len(r2.edges)
        assert [e.source for e in r1.edges] == [e.source for e in r2.edges]

    def test_returns_detector_result(self):
        ctx = _ctx("")
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)

    def test_id_format(self):
        src = '''\
class MyClass:
    pass

def my_func():
    pass
'''
        ctx = _ctx(src, path="src/module.py")
        r = self.detector.detect(ctx)
        class_node = next(n for n in r.nodes if n.kind == NodeKind.CLASS)
        assert class_node.id == "py:src/module.py:class:MyClass"

        func_node = next(n for n in r.nodes if n.kind == NodeKind.METHOD)
        assert func_node.id == "py:src/module.py:func:my_func"
