"""Tests for GitLabCIDetector."""

from code_intelligence.detectors.config.gitlab_ci import GitLabCIDetector
from code_intelligence.detectors.base import DetectorContext
from code_intelligence.models.graph import NodeKind, EdgeKind


def _ctx(data, path=".gitlab-ci.yml"):
    return DetectorContext(
        file_path=path, language="yaml", content=b"",
        parsed_data={"type": "yaml", "file": path, "data": data},
    )


def test_detects_stages():
    ctx = _ctx({"stages": ["build", "test", "deploy"]})
    r = GitLabCIDetector().detect(ctx)
    stage_nodes = [n for n in r.nodes if n.kind == NodeKind.CONFIG_KEY]
    assert len(stage_nodes) == 3


def test_detects_jobs():
    ctx = _ctx({
        "stages": ["build", "test"],
        "build_app": {"stage": "build", "script": ["mvn clean package"], "image": "maven:3.9"},
        "unit_tests": {"stage": "test", "script": ["mvn test"], "needs": ["build_app"]},
    })
    r = GitLabCIDetector().detect(ctx)
    jobs = [n for n in r.nodes if n.kind == NodeKind.METHOD]
    assert len(jobs) == 2
    # Check needs edge
    deps = [e for e in r.edges if e.kind == EdgeKind.DEPENDS_ON]
    assert len(deps) >= 1


def test_detects_tools_in_script():
    ctx = _ctx({
        "deploy": {"stage": "deploy", "script": ["docker build .", "helm upgrade --install app ./chart"]},
    })
    r = GitLabCIDetector().detect(ctx)
    jobs = [n for n in r.nodes if n.kind == NodeKind.METHOD]
    assert len(jobs) == 1
    assert "docker" in jobs[0].properties.get("tools", [])
    assert "helm" in jobs[0].properties.get("tools", [])


def test_skips_non_gitlab_files():
    ctx = DetectorContext(file_path="config.yml", language="yaml", content=b"",
                          parsed_data={"type": "yaml", "file": "config.yml", "data": {"key": "value"}})
    r = GitLabCIDetector().detect(ctx)
    assert len(r.nodes) == 0


def test_pipeline_module_node():
    ctx = _ctx({"build": {"script": ["echo hello"]}})
    r = GitLabCIDetector().detect(ctx)
    modules = [n for n in r.nodes if n.kind == NodeKind.MODULE]
    assert len(modules) == 1


def test_determinism():
    ctx = _ctx({"stages": ["a", "b"], "job_a": {"stage": "a", "script": ["echo"]}, "job_b": {"stage": "b", "script": ["echo"], "needs": ["job_a"]}})
    r1 = GitLabCIDetector().detect(ctx)
    r2 = GitLabCIDetector().detect(ctx)
    assert len(r1.nodes) == len(r2.nodes)
    assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]
