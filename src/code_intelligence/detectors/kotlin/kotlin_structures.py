"""Regex-based Kotlin structures detector for Kotlin source files."""

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

# FIX: Added sealed, enum, annotation, value, inline modifiers (original only
# had data, open, abstract).
_KOTLIN_IMPORT_RE = re.compile(r'^\s*import\s+([\w.]+)', re.MULTILINE)
_KOTLIN_CLASS_RE = re.compile(
    r'^\s*(?:(?:data|open|abstract|sealed|enum|annotation|value|inline)\s+)*class\s+(\w+)'
    r'(?:\s*(?:\(.*?\))?\s*:\s*([\w\s,.<>]+))?',
    re.MULTILINE,
)
_KOTLIN_INTERFACE_RE = re.compile(r'^\s*interface\s+(\w+)', re.MULTILINE)
# FIX: Added inline fun and override fun matching (original missed inline fun).
_KOTLIN_FUN_RE = re.compile(
    r'^\s*(?:(?:override|inline|private|protected|internal|public)\s+)*(?:fun|suspend\s+fun)\s+(\w+)\s*\(',
    re.MULTILINE,
)
_KOTLIN_OBJECT_RE = re.compile(r'^\s*object\s+(\w+)', re.MULTILINE)


def _find_line_number(text: str, pos: int) -> int:
    """Return the 1-based line number for a character offset."""
    return text[:pos].count("\n") + 1


class KotlinStructuresDetector:
    """Detects Kotlin imports, classes, interfaces, objects, and functions."""

    name: str = "kotlin_structures"
    supported_languages: tuple[str, ...] = ("kotlin",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        file_node_id = ctx.file_path

        for m in _KOTLIN_IMPORT_RE.finditer(text):
            target = m.group(1)
            result.edges.append(GraphEdge(
                source=file_node_id,
                target=target,
                kind=EdgeKind.IMPORTS,
                label=f"{ctx.file_path} imports {target}",
            ))

        for m in _KOTLIN_CLASS_RE.finditer(text):
            class_name = m.group(1)
            supertypes_str = m.group(2)
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
            if supertypes_str:
                for st in supertypes_str.split(","):
                    st = st.strip().split("(")[0].split("<")[0].strip()
                    if st:
                        result.edges.append(GraphEdge(
                            source=node_id,
                            target=st,
                            kind=EdgeKind.EXTENDS,
                            label=f"{class_name} extends {st}",
                        ))

        for m in _KOTLIN_INTERFACE_RE.finditer(text):
            iface_name = m.group(1)
            result.nodes.append(GraphNode(
                id=f"{ctx.file_path}:{iface_name}",
                kind=NodeKind.INTERFACE,
                label=iface_name,
                fqn=iface_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=_find_line_number(text, m.start()),
                ),
            ))

        for m in _KOTLIN_OBJECT_RE.finditer(text):
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

        for m in _KOTLIN_FUN_RE.finditer(text):
            fn_name = m.group(1)
            result.nodes.append(GraphNode(
                id=f"{ctx.file_path}:{fn_name}",
                kind=NodeKind.METHOD,
                label=fn_name,
                fqn=fn_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=_find_line_number(text, m.start()),
                ),
            ))

        return result
