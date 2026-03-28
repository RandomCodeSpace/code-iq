"""GitLab CI pipeline detector for .gitlab-ci.yml definitions."""

from __future__ import annotations

from typing import Any

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

_GITLAB_CI_KEYWORDS = frozenset({
    "stages",
    "variables",
    "default",
    "workflow",
    "include",
    "image",
    "services",
    "before_script",
    "after_script",
    "cache",
})

_TOOL_KEYWORDS = (
    "docker",
    "helm",
    "kubectl",
    "terraform",
    "maven",
    "gradle",
    "npm",
    "pip",
)


def _is_gitlab_ci_file(ctx: DetectorContext) -> bool:
    """Check whether the file is a GitLab CI configuration file."""
    return ctx.file_path.endswith(".gitlab-ci.yml")


def _detect_tools(scripts: list[Any]) -> list[str]:
    """Scan script lines for known tool keywords."""
    tools: list[str] = []
    for line in scripts:
        line_str = str(line)
        for tool in _TOOL_KEYWORDS:
            if tool in line_str and tool not in tools:
                tools.append(tool)
    return tools


class GitLabCIDetector:
    """Detects stages, jobs, dependencies, and tool usage from GitLab CI YAML files."""

    name: str = "gitlab_ci"
    supported_languages: tuple[str, ...] = ("yaml",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()

        if not _is_gitlab_ci_file(ctx):
            return result

        if not ctx.parsed_data:
            return result

        data = ctx.parsed_data.get("data")
        if not isinstance(data, dict):
            return result

        fp = ctx.file_path
        pipeline_id = f"gitlab:{fp}:pipeline"

        # Pipeline MODULE node
        result.nodes.append(GraphNode(
            id=pipeline_id,
            kind=NodeKind.MODULE,
            label=f"pipeline:{fp}",
            fqn=pipeline_id,
            module=ctx.module_name,
            location=SourceLocation(file_path=fp),
            properties={"pipeline_file": fp},
        ))

        # Stages
        stages = data.get("stages")
        if isinstance(stages, list):
            for stage_name in stages:
                stage_str = str(stage_name)
                result.nodes.append(GraphNode(
                    id=f"gitlab:{fp}:stage:{stage_str}",
                    kind=NodeKind.CONFIG_KEY,
                    label=f"stage:{stage_str}",
                    module=ctx.module_name,
                    location=SourceLocation(file_path=fp),
                    properties={"stage": stage_str},
                ))

        # Include directives
        includes = data.get("include")
        if includes is not None:
            if isinstance(includes, str):
                includes = [includes]
            if isinstance(includes, list):
                for inc in includes:
                    if isinstance(inc, str):
                        target = inc
                    elif isinstance(inc, dict):
                        target = inc.get("local") or inc.get("file") or inc.get("template") or str(inc)
                    else:
                        target = str(inc)
                    result.edges.append(GraphEdge(
                        source=pipeline_id,
                        target=str(target),
                        kind=EdgeKind.IMPORTS,
                        label=f"includes {target}",
                    ))

        # Collect job names first for edge resolution
        job_names: list[str] = []
        for key in data:
            key_str = str(key)
            if key_str in _GITLAB_CI_KEYWORDS:
                continue
            val = data[key]
            if isinstance(val, dict):
                job_names.append(key_str)

        job_ids: dict[str, str] = {}
        for name in job_names:
            job_ids[name] = f"gitlab:{fp}:job:{name}"

        # Process each job
        for job_name in job_names:
            job_def = data[job_name]
            job_id = job_ids[job_name]

            props: dict[str, Any] = {}

            # Stage property
            stage_val = job_def.get("stage")
            if stage_val is not None:
                props["stage"] = str(stage_val)

            # Image property
            image_val = job_def.get("image")
            if image_val is not None:
                props["image"] = str(image_val)

            # Script tool detection
            scripts = job_def.get("script")
            if isinstance(scripts, list):
                tools = _detect_tools(scripts)
                if tools:
                    props["tools"] = tools

            # Job METHOD node
            result.nodes.append(GraphNode(
                id=job_id,
                kind=NodeKind.METHOD,
                label=job_name,
                fqn=job_id,
                module=ctx.module_name,
                location=SourceLocation(file_path=fp),
                properties=props,
            ))

            # CONTAINS edge: pipeline -> job
            result.edges.append(GraphEdge(
                source=pipeline_id,
                target=job_id,
                kind=EdgeKind.CONTAINS,
                label=f"pipeline contains job {job_name}",
            ))

            # needs: dependencies
            needs = job_def.get("needs")
            if isinstance(needs, str):
                needs = [needs]
            if isinstance(needs, list):
                for dep in needs:
                    # needs can be a string or a dict with "job" key
                    if isinstance(dep, dict):
                        dep_str = str(dep.get("job", ""))
                    else:
                        dep_str = str(dep)
                    if dep_str and dep_str in job_ids:
                        result.edges.append(GraphEdge(
                            source=job_id,
                            target=job_ids[dep_str],
                            kind=EdgeKind.DEPENDS_ON,
                            label=f"job {job_name} needs {dep_str}",
                        ))

            # extends: template inheritance
            extends = job_def.get("extends")
            if extends is not None:
                if isinstance(extends, str):
                    extends = [extends]
                if isinstance(extends, list):
                    for parent in extends:
                        parent_str = str(parent)
                        if parent_str in job_ids:
                            result.edges.append(GraphEdge(
                                source=job_id,
                                target=job_ids[parent_str],
                                kind=EdgeKind.EXTENDS,
                                label=f"job {job_name} extends {parent_str}",
                            ))

        return result
