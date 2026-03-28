"""Regex-based Scala structures detector for Scala source files."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

_SCALA_IMPORT_RE = re.compile(r'^\s*import\s+([\w.]+)', re.MULTILINE)
_SCALA_CLASS_RE = re.compile(
    r'^\s*(?:case\s+)?class\s+(\w+)(?:\s+extends\s+(\w+))?(?:\s+with\s+([\w\s,]+))?',
    re.MULTILINE,
)
_SCALA_TRAIT_RE = re.compile(r'^\s*trait\s+(\w+)', re.MULTILINE)
_SCALA_OBJECT_RE = re.compile(r'^\s*object\s+(\w+)', re.MULTILINE)
_SCALA_DEF_RE = re.compile(r'^\s*def\s+(\w+)\s*[\[(]', re.MULTILINE)


def _find_line_number(text: str, pos: int) -> int:
    """Return the 1-based line number for a character offset."""
    return text[:pos].count("\n") + 1


class ScalaStructuresDetector:
    """Detects Scala imports, classes, traits, objects, and methods."""

    name: str = "scala_structures"
    supported_languages: tuple[str, ...] = ("scala",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        file_node_id = ctx.file_path

        for m in _SCALA_IMPORT_RE.finditer(text):
            target = m.group(1)
            result.edges.append(GraphEdge(
                source=file_node_id,
                target=target,
                kind=EdgeKind.IMPORTS,
                label=f"{ctx.file_path} imports {target}",
            ))

        for m in _SCALA_CLASS_RE.finditer(text):
            class_name = m.group(1)
            base_class = m.group(2)
            traits_str = m.group(3)
            node_id = f"{ctx.file_path}:{class_name}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.CLASS,
                label=class_name,
                fqn=class_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=_find_line_number(text, m.start()),
                ),
            ))
            if base_class:
                result.edges.append(GraphEdge(
                    source=node_id,
                    target=base_class,
                    kind=EdgeKind.EXTENDS,
                    label=f"{class_name} extends {base_class}",
                ))
            if traits_str:
                for trait in traits_str.split(","):
                    trait = trait.strip()
                    if trait:
                        result.edges.append(GraphEdge(
                            source=node_id,
                            target=trait,
                            kind=EdgeKind.IMPLEMENTS,
                            label=f"{class_name} implements {trait}",
                        ))

        for m in _SCALA_TRAIT_RE.finditer(text):
            trait_name = m.group(1)
            result.nodes.append(GraphNode(
                id=f"{ctx.file_path}:{trait_name}",
                kind=NodeKind.INTERFACE,
                label=trait_name,
                fqn=trait_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=_find_line_number(text, m.start()),
                ),
                properties={"type": "trait"},
            ))

        for m in _SCALA_OBJECT_RE.finditer(text):
            obj_name = m.group(1)
            result.nodes.append(GraphNode(
                id=f"{ctx.file_path}:{obj_name}",
                kind=NodeKind.CLASS,
                label=obj_name,
                fqn=obj_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=_find_line_number(text, m.start()),
                ),
                properties={"type": "object"},
            ))

        for m in _SCALA_DEF_RE.finditer(text):
            method_name = m.group(1)
            result.nodes.append(GraphNode(
                id=f"{ctx.file_path}:{method_name}",
                kind=NodeKind.METHOD,
                label=method_name,
                fqn=method_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=_find_line_number(text, m.start()),
                ),
            ))

        return result
