"""Module dependency detector for Maven (pom.xml) and Gradle build files."""

from __future__ import annotations

import re
from typing import Any
from xml.etree import ElementTree

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

_POM_NS = {"m": "http://maven.apache.org/POM/4.0.0"}

# Gradle patterns
_GRADLE_DEPENDENCY_RE = re.compile(
    r"(?:implementation|api|compile|compileOnly|runtimeOnly|testImplementation)\s+"
    r"(?:project\s*\(\s*['\"]([^'\"]+)['\"]\s*\)"
    r"|['\"]([^'\"]+)['\"])"
)
_GRADLE_SETTINGS_MODULE_RE = re.compile(r"include\s+['\"]([^'\"]+)['\"]")


class ModuleDepsDetector:
    """Detects Maven/Gradle module declarations and inter-module dependencies."""

    name: str = "module_deps"
    supported_languages: tuple[str, ...] = ("java", "xml", "gradle")

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        if ctx.file_path.endswith("pom.xml"):
            return self._detect_maven(ctx)
        elif ctx.file_path.endswith((".gradle", ".gradle.kts")):
            return self._detect_gradle(ctx)
        elif ctx.file_path.endswith("settings.gradle") or ctx.file_path.endswith("settings.gradle.kts"):
            return self._detect_gradle_settings(ctx)
        return DetectorResult()

    def _detect_maven(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")

        try:
            root = ElementTree.fromstring(text)
        except ElementTree.ParseError:
            return result

        # Determine this module's coordinates
        # Try with namespace first, then without
        group_id_el = root.find("m:groupId", _POM_NS)
        if group_id_el is None:
            group_id_el = root.find("groupId")
        artifact_id_el = root.find("m:artifactId", _POM_NS)
        if artifact_id_el is None:
            artifact_id_el = root.find("artifactId")

        if artifact_id_el is None:
            return result

        group_id = group_id_el.text if group_id_el is not None else "unknown"
        artifact_id = artifact_id_el.text or "unknown"
        module_id = f"module:{group_id}:{artifact_id}"

        result.nodes.append(GraphNode(
            id=module_id,
            kind=NodeKind.MODULE,
            label=artifact_id,
            fqn=f"{group_id}:{artifact_id}",
            module=ctx.module_name,
            location=SourceLocation(file_path=ctx.file_path, line_start=1),
            properties={"group_id": group_id, "artifact_id": artifact_id, "build_tool": "maven"},
        ))

        # Detect sub-modules
        for modules_el in (root.findall("m:modules/m:module", _POM_NS) + root.findall("modules/module")):
            if modules_el.text:
                sub_module = modules_el.text
                sub_id = f"module:{group_id}:{sub_module}"
                result.nodes.append(GraphNode(
                    id=sub_id,
                    kind=NodeKind.MODULE,
                    label=sub_module,
                    fqn=f"{group_id}:{sub_module}",
                    properties={"build_tool": "maven", "parent": artifact_id},
                ))
                result.edges.append(GraphEdge(
                    source=module_id,
                    target=sub_id,
                    kind=EdgeKind.CONTAINS,
                    label=f"{artifact_id} contains {sub_module}",
                ))

        # Detect dependencies
        dep_paths = (
            root.findall("m:dependencies/m:dependency", _POM_NS)
            + root.findall("dependencies/dependency")
        )
        for dep in dep_paths:
            dep_group_el = dep.find("m:groupId", _POM_NS)
            if dep_group_el is None:
                dep_group_el = dep.find("groupId")
            dep_artifact_el = dep.find("m:artifactId", _POM_NS)
            if dep_artifact_el is None:
                dep_artifact_el = dep.find("artifactId")
            if dep_artifact_el is not None:
                dep_group = dep_group_el.text if dep_group_el is not None else "unknown"
                dep_artifact = dep_artifact_el.text or "unknown"
                dep_id = f"module:{dep_group}:{dep_artifact}"
                result.edges.append(GraphEdge(
                    source=module_id,
                    target=dep_id,
                    kind=EdgeKind.DEPENDS_ON,
                    label=f"{artifact_id} depends on {dep_artifact}",
                    properties={"group_id": dep_group, "artifact_id": dep_artifact},
                ))

        return result

    def _detect_gradle(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        lines = text.split("\n")

        # Try to infer module name from file path
        module_name = ctx.module_name or ctx.file_path.rsplit("/", 1)[0].rsplit("/", 1)[-1]
        module_id = f"module:{module_name}"

        result.nodes.append(GraphNode(
            id=module_id,
            kind=NodeKind.MODULE,
            label=module_name,
            fqn=module_name,
            module=ctx.module_name,
            location=SourceLocation(file_path=ctx.file_path, line_start=1),
            properties={"build_tool": "gradle"},
        ))

        for i, line in enumerate(lines):
            m = _GRADLE_DEPENDENCY_RE.search(line)
            if not m:
                continue

            project_dep = m.group(1)  # project(':submodule')
            external_dep = m.group(2)  # 'group:artifact:version'

            if project_dep:
                dep_name = project_dep.lstrip(":")
                dep_id = f"module:{dep_name}"
                result.edges.append(GraphEdge(
                    source=module_id,
                    target=dep_id,
                    kind=EdgeKind.DEPENDS_ON,
                    label=f"{module_name} depends on {dep_name}",
                    properties={"type": "project"},
                ))
            elif external_dep and ":" in external_dep:
                parts = external_dep.split(":")
                dep_id = f"module:{parts[0]}:{parts[1]}" if len(parts) >= 2 else f"module:{external_dep}"
                result.edges.append(GraphEdge(
                    source=module_id,
                    target=dep_id,
                    kind=EdgeKind.DEPENDS_ON,
                    label=f"{module_name} depends on {external_dep}",
                    properties={"coordinate": external_dep, "type": "external"},
                ))

        return result

    def _detect_gradle_settings(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")

        for m in _GRADLE_SETTINGS_MODULE_RE.finditer(text):
            module_path = m.group(1).lstrip(":")
            module_id = f"module:{module_path}"
            result.nodes.append(GraphNode(
                id=module_id,
                kind=NodeKind.MODULE,
                label=module_path,
                fqn=module_path,
                location=SourceLocation(file_path=ctx.file_path),
                properties={"build_tool": "gradle"},
            ))

        return result
