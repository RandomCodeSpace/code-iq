"""Tests for DockerfileDetector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.iac.dockerfile import DockerfileDetector
from code_intelligence.models.graph import EdgeKind, NodeKind


def _ctx(content, path="Dockerfile"):
    return DetectorContext(
        file_path=path,
        language="dockerfile",
        content=content.encode(),
    )


class TestDockerfileDetector:
    def setup_method(self):
        self.detector = DockerfileDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "dockerfile"
        assert self.detector.supported_languages == ("dockerfile",)

    def test_detects_from_expose_env(self):
        dockerfile = """\
FROM python:3.12-slim AS builder
ENV APP_HOME=/app
WORKDIR $APP_HOME
COPY . .
RUN pip install -r requirements.txt
EXPOSE 8080
LABEL maintainer=team@example.com
"""
        ctx = _ctx(dockerfile)
        r = self.detector.detect(ctx)
        # FROM -> INFRA_RESOURCE
        infra = [n for n in r.nodes if n.kind == NodeKind.INFRA_RESOURCE]
        assert len(infra) == 1
        assert infra[0].properties["image"] == "python:3.12-slim"
        assert infra[0].properties.get("stage_alias") == "builder"
        # EXPOSE -> ENDPOINT
        endpoints = [n for n in r.nodes if n.kind == NodeKind.ENDPOINT]
        assert len(endpoints) == 1
        assert endpoints[0].properties["port"] == "8080"
        # ENV -> CONFIG_DEFINITION
        config_defs = [n for n in r.nodes if n.kind == NodeKind.CONFIG_DEFINITION]
        env_defs = [n for n in config_defs if n.properties.get("env_key")]
        assert len(env_defs) == 1
        assert env_defs[0].properties["env_key"] == "APP_HOME"
        # DEPENDS_ON edge to base image
        dep_edges = [e for e in r.edges if e.kind == EdgeKind.DEPENDS_ON]
        assert len(dep_edges) == 1
        assert dep_edges[0].target == "python:3.12-slim"

    def test_irrelevant_content_returns_empty(self):
        ctx = _ctx("# This is just a comment\nRUN echo hello\n")
        r = self.detector.detect(ctx)
        assert r.nodes == []
        assert r.edges == []

    def test_determinism(self):
        dockerfile = "FROM node:18\nEXPOSE 3000\n"
        ctx = _ctx(dockerfile)
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]

    def test_returns_detector_result(self):
        ctx = _ctx("")
        result = self.detector.detect(ctx)
        assert isinstance(result, DetectorResult)

    def test_multi_stage_build(self):
        ctx = _ctx("FROM golang:1.21 AS builder\nRUN go build\nFROM alpine:3.19\nCOPY --from=builder /app /app")
        r = self.detector.detect(ctx)
        infra = [n for n in r.nodes if n.kind == NodeKind.INFRA_RESOURCE]
        assert len(infra) == 2
        # First stage should have build_stage property
        builder = [n for n in infra if "builder" in str(n.properties)]
        assert len(builder) >= 1
        # Should have DEPENDS_ON edge for COPY --from
        deps = [e for e in r.edges if e.kind == EdgeKind.DEPENDS_ON]
        assert len(deps) >= 1

    def test_arg_detection(self):
        ctx = _ctx("ARG VERSION=1.0\nFROM myimage:${VERSION}")
        r = self.detector.detect(ctx)
        args = [n for n in r.nodes if n.kind == NodeKind.CONFIG_DEFINITION and "arg" in n.id]
        assert len(args) >= 1
