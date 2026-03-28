"""Tests for DockerComposeDetector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.config.docker_compose import DockerComposeDetector
from code_intelligence.models.graph import EdgeKind, NodeKind


def _ctx(parsed_data, path="docker-compose.yml"):
    return DetectorContext(
        file_path=path,
        language="yaml",
        content=b"",
        parsed_data=parsed_data,
    )


class TestDockerComposeDetector:
    def setup_method(self):
        self.detector = DockerComposeDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "docker_compose"
        assert self.detector.supported_languages == ("yaml",)

    def test_detects_services(self):
        ctx = _ctx({
            "type": "yaml",
            "data": {
                "services": {
                    "web": {"image": "nginx:latest", "ports": ["80:80"]},
                    "db": {"image": "postgres:15"},
                },
            },
        })
        r = self.detector.detect(ctx)
        infra_nodes = [n for n in r.nodes if n.kind == NodeKind.INFRA_RESOURCE]
        labels = {n.label for n in infra_nodes}
        assert "web" in labels
        assert "db" in labels
        assert infra_nodes[0].properties.get("image") in ("nginx:latest", "postgres:15")

    def test_non_compose_file_returns_empty(self):
        ctx = _ctx(
            {"type": "yaml", "data": {"name": "not-compose"}},
            path="config.yml",
        )
        r = self.detector.detect(ctx)
        assert r.nodes == []
        assert r.edges == []

    def test_determinism(self):
        ctx = _ctx({
            "type": "yaml",
            "data": {
                "services": {
                    "api": {"image": "node:18"},
                    "redis": {"image": "redis:7"},
                },
            },
        })
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]

    def test_depends_on_edges(self):
        ctx = _ctx({
            "type": "yaml",
            "data": {
                "services": {
                    "web": {"image": "nginx", "depends_on": ["db"]},
                    "db": {"image": "postgres"},
                },
            },
        })
        r = self.detector.detect(ctx)
        dep_edges = [e for e in r.edges if e.kind == EdgeKind.DEPENDS_ON]
        assert len(dep_edges) == 1
        assert "web" in dep_edges[0].source
        assert "db" in dep_edges[0].target

    def test_returns_detector_result(self):
        ctx = _ctx(None)
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)
