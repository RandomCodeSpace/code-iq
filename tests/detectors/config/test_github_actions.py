"""Tests for GitHubActionsDetector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.config.github_actions import GitHubActionsDetector
from code_intelligence.models.graph import EdgeKind, NodeKind


def _ctx(parsed_data, path=".github/workflows/ci.yml"):
    return DetectorContext(
        file_path=path,
        language="yaml",
        content=b"",
        parsed_data=parsed_data,
    )


class TestGitHubActionsDetector:
    def setup_method(self):
        self.detector = GitHubActionsDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "github_actions"
        assert self.detector.supported_languages == ("yaml",)

    def test_detects_workflow_and_jobs(self):
        ctx = _ctx({
            "type": "yaml",
            "data": {
                "name": "CI",
                "on": {"push": {"branches": ["main"]}},
                "jobs": {
                    "build": {
                        "runs-on": "ubuntu-latest",
                        "steps": [{"run": "echo hello"}],
                    },
                    "test": {
                        "runs-on": "ubuntu-latest",
                        "needs": "build",
                        "steps": [{"run": "pytest"}],
                    },
                },
            },
        })
        r = self.detector.detect(ctx)
        # Workflow MODULE node
        modules = [n for n in r.nodes if n.kind == NodeKind.MODULE]
        assert len(modules) == 1
        assert modules[0].label == "CI"
        # Job METHOD nodes
        jobs = [n for n in r.nodes if n.kind == NodeKind.METHOD]
        assert len(jobs) == 2
        # Trigger CONFIG_KEY node
        triggers = [n for n in r.nodes if n.kind == NodeKind.CONFIG_KEY]
        assert any("push" in n.label for n in triggers)
        # DEPENDS_ON edge from test -> build
        dep_edges = [e for e in r.edges if e.kind == EdgeKind.DEPENDS_ON]
        assert len(dep_edges) == 1

    def test_non_workflow_file_returns_empty(self):
        ctx = _ctx(
            {"type": "yaml", "data": {"name": "something"}},
            path="config/app.yml",
        )
        r = self.detector.detect(ctx)
        assert r.nodes == []
        assert r.edges == []

    def test_determinism(self):
        ctx = _ctx({
            "type": "yaml",
            "data": {
                "name": "Deploy",
                "on": "push",
                "jobs": {
                    "deploy": {"runs-on": "ubuntu-latest", "steps": []},
                },
            },
        })
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]

    def test_returns_detector_result(self):
        ctx = _ctx(None)
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)
