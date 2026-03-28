"""Tests for PackageJsonDetector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.config.package_json import PackageJsonDetector
from code_intelligence.models.graph import EdgeKind, NodeKind


def _ctx(parsed_data, path="package.json"):
    return DetectorContext(
        file_path=path,
        language="json",
        content=b"",
        parsed_data=parsed_data,
    )


class TestPackageJsonDetector:
    def setup_method(self):
        self.detector = PackageJsonDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "package_json"
        assert self.detector.supported_languages == ("json",)

    def test_detects_package_and_dependencies(self):
        ctx = _ctx({
            "type": "json",
            "data": {
                "name": "my-app",
                "version": "1.0.0",
                "scripts": {"build": "tsc", "test": "jest"},
                "dependencies": {"express": "^4.18.0"},
                "devDependencies": {"typescript": "^5.0.0"},
            },
        })
        r = self.detector.detect(ctx)
        # MODULE node for the package
        modules = [n for n in r.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        assert modules[0].label == "my-app"
        assert modules[0].properties["version"] == "1.0.0"
        # Script METHOD nodes
        methods = [n for n in r.nodes if n.kind == NodeKind.METHOD]
        assert len(methods) == 2
        script_labels = {n.label for n in methods}
        assert "npm run build" in script_labels
        assert "npm run test" in script_labels
        # DEPENDS_ON edges for dependencies
        dep_edges = [e for e in r.edges if e.kind == EdgeKind.DEPENDS_ON]
        dep_targets = {e.target for e in dep_edges}
        assert "npm:express" in dep_targets
        assert "npm:typescript" in dep_targets

    def test_non_package_json_returns_empty(self):
        ctx = _ctx(
            {"type": "json", "data": {"name": "test"}},
            path="tsconfig.json",
        )
        r = self.detector.detect(ctx)
        assert r.nodes == []
        assert r.edges == []

    def test_determinism(self):
        ctx = _ctx({
            "type": "json",
            "data": {
                "name": "test-pkg",
                "dependencies": {"lodash": "^4.0.0"},
            },
        })
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]
        assert len(r1.edges) == len(r2.edges)

    def test_returns_detector_result(self):
        ctx = _ctx(None)
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)
