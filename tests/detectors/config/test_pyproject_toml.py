"""Tests for pyproject.toml detector."""

from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.config.pyproject_toml import PyprojectTomlDetector, _parse_dep_name
from osscodeiq.models.graph import NodeKind, EdgeKind


def _ctx(content: str, path: str = "pyproject.toml"):
    return DetectorContext(
        file_path=path, language="toml", content=content.encode(), module_name="test",
    )


class TestPyprojectTomlDetector:
    def setup_method(self):
        self.detector = PyprojectTomlDetector()

    def test_wrong_filename(self):
        result = self.detector.detect(_ctx("[project]\nname = 'foo'", path="settings.toml"))
        assert len(result.nodes) == 0

    def test_pep621_project(self):
        content = """\
[project]
name = "my-package"
version = "1.0.0"
description = "A test package"
dependencies = [
    "requests>=2.28",
    "click",
    "pydantic[email]>=2.0",
]

[project.scripts]
my-cli = "my_package.cli:main"
"""
        result = self.detector.detect(_ctx(content))
        modules = [n for n in result.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        assert modules[0].label == "my-package"
        assert modules[0].properties["version"] == "1.0.0"
        assert modules[0].properties["description"] == "A test package"

        dep_edges = [e for e in result.edges if e.kind == EdgeKind.DEPENDS_ON]
        dep_targets = {e.target for e in dep_edges}
        assert "pypi:requests" in dep_targets
        assert "pypi:click" in dep_targets
        assert "pypi:pydantic" in dep_targets

        scripts = [n for n in result.nodes if n.kind == NodeKind.CONFIG_DEFINITION]
        assert len(scripts) == 1
        assert scripts[0].label == "my-cli"
        assert scripts[0].properties["target"] == "my_package.cli:main"

        contains = [e for e in result.edges if e.kind == EdgeKind.CONTAINS]
        assert len(contains) == 1

    def test_poetry_project(self):
        content = """\
[tool.poetry]
name = "poetry-app"
version = "2.0.0"
description = "A poetry project"

[tool.poetry.dependencies]
python = "^3.11"
fastapi = "^0.100"
uvicorn = "^0.23"

[tool.poetry.scripts]
serve = "poetry_app.main:run"
"""
        result = self.detector.detect(_ctx(content))
        modules = [n for n in result.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        assert modules[0].label == "poetry-app"

        dep_edges = [e for e in result.edges if e.kind == EdgeKind.DEPENDS_ON]
        dep_targets = {e.target for e in dep_edges}
        # python should be skipped
        assert "pypi:python" not in dep_targets
        assert "pypi:fastapi" in dep_targets
        assert "pypi:uvicorn" in dep_targets

        scripts = [n for n in result.nodes if n.kind == NodeKind.CONFIG_DEFINITION]
        assert len(scripts) == 1

    def test_empty_toml(self):
        # Empty toml is valid and produces a module node with filepath as name
        result = self.detector.detect(_ctx(""))
        modules = [n for n in result.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        assert modules[0].label == "pyproject.toml"

    def test_invalid_toml(self):
        result = self.detector.detect(_ctx("not valid toml [[["))
        assert len(result.nodes) == 0

    def test_no_dependencies(self):
        content = """\
[project]
name = "bare-project"
"""
        result = self.detector.detect(_ctx(content))
        modules = [n for n in result.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        dep_edges = [e for e in result.edges if e.kind == EdgeKind.DEPENDS_ON]
        assert len(dep_edges) == 0

    def test_determinism(self):
        content = """\
[project]
name = "det-test"
dependencies = ["a", "b", "c"]
"""
        r1 = self.detector.detect(_ctx(content))
        r2 = self.detector.detect(_ctx(content))
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]
        assert [(e.source, e.target) for e in r1.edges] == [(e.source, e.target) for e in r2.edges]


class TestParseDepName:
    def test_simple(self):
        assert _parse_dep_name("requests") == "requests"

    def test_version_spec(self):
        assert _parse_dep_name("requests>=2.28") == "requests"

    def test_extras(self):
        assert _parse_dep_name("pydantic[email]>=2.0") == "pydantic"

    def test_empty(self):
        assert _parse_dep_name("") is None

    def test_whitespace(self):
        assert _parse_dep_name("  requests  ") == "requests"
