"""Regex-based Python structures detector for Python source files."""

from __future__ import annotations

import re

from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.utils import decode_text, find_line_number
from osscodeiq.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

_CLASS_RE = re.compile(r'^class\s+(\w+)(?:\(([^)]*)\))?:', re.MULTILINE)
_FUNC_RE = re.compile(r'^([^\S\n]*)(async\s+)?def\s+(\w+)\s*\(', re.MULTILINE)
_IMPORT_RE = re.compile(r'^(?:from\s+([\w.]+)\s+)?import\s+([\w., ]+)', re.MULTILINE)
_DECORATOR_RE = re.compile(r'^([^\S\n]*)@(\w[\w.]*)', re.MULTILINE)
_ALL_RE = re.compile(r'__all__\s*=\s*\[([^\]]*)\]', re.DOTALL)


def _collect_decorators(text: str) -> dict[int, list[str]]:
    """Map each decorator's line number to the decorator name.

    Returns a dict keyed by 1-based line number of the decorator.
    """
    result: dict[int, list[str]] = {}
    for m in _DECORATOR_RE.finditer(text):
        line = find_line_number(text, m.start())
        result.setdefault(line, []).append(m.group(2))
    return result


def _find_decorators_for_line(
    decorator_map: dict[int, list[str]], target_line: int
) -> list[str]:
    """Collect all decorator names immediately above a target line."""
    decorators: list[str] = []
    line = target_line - 1
    while line in decorator_map:
        decorators.extend(decorator_map[line])
        line -= 1
    # Reverse so top-most decorator is first
    decorators.reverse()
    return decorators


def _find_enclosing_class(
    class_ranges: list[tuple[str, int, int]], line: int, func_indent: int
) -> str | None:
    """Find the class name that encloses a given line number.

    A function is considered inside a class if it appears after the class
    declaration and is indented more than the class itself.

    class_ranges is a list of (class_name, start_line, indent_col).
    """
    for class_name, start_line, class_indent in reversed(class_ranges):
        if line > start_line and func_indent > class_indent:
            return class_name
    return None


class PythonStructuresDetector:
    """Detects Python classes, functions, imports, decorators, and __all__ exports."""

    name: str = "python_structures"
    supported_languages: tuple[str, ...] = ("python",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)
        fp = ctx.file_path
        file_node_id = fp

        # Collect decorators by line number
        decorator_map = _collect_decorators(text)

        # __all__ exports
        all_match = _ALL_RE.search(text)
        all_exports: list[str] | None = None
        if all_match:
            raw = all_match.group(1)
            # Extract quoted names
            all_exports = re.findall(r"""['"](\w+)['"]""", raw)

        # Classes — track them for method association
        class_ranges: list[tuple[str, int, int]] = []  # (name, line, indent_col)
        for m in _CLASS_RE.finditer(text):
            class_name = m.group(1)
            bases_str = m.group(2)
            line = find_line_number(text, m.start())
            node_id = f"py:{fp}:class:{class_name}"

            # Find indent of the class declaration
            line_start_offset = text.rfind("\n", 0, m.start()) + 1
            indent = m.start() - line_start_offset

            class_ranges.append((class_name, line, indent))

            annotations = _find_decorators_for_line(decorator_map, line)

            properties: dict = {}
            if bases_str:
                bases = [b.strip() for b in bases_str.split(",") if b.strip()]
                properties["bases"] = bases
            if all_exports and class_name in all_exports:
                properties["exported"] = True

            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.CLASS,
                label=class_name,
                fqn=class_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=fp,
                    line_start=line,
                ),
                annotations=annotations,
                properties=properties,
            ))

            # EXTENDS edges for base classes
            if bases_str:
                bases = [b.strip() for b in bases_str.split(",") if b.strip()]
                for base in bases:
                    result.edges.append(GraphEdge(
                        source=node_id,
                        target=base,
                        kind=EdgeKind.EXTENDS,
                        label=f"{class_name} extends {base}",
                    ))

        # Functions and methods
        for m in _FUNC_RE.finditer(text):
            indent_str = m.group(1)
            is_async = m.group(2) is not None
            func_name = m.group(3)
            line = find_line_number(text, m.start())
            indent_len = len(indent_str)

            annotations = _find_decorators_for_line(decorator_map, line)

            properties: dict = {}
            if is_async:
                properties["async"] = True
            if all_exports and func_name in all_exports:
                properties["exported"] = True

            if indent_len == 0:
                # Top-level function
                node_id = f"py:{fp}:func:{func_name}"
                result.nodes.append(GraphNode(
                    id=node_id,
                    kind=NodeKind.METHOD,
                    label=func_name,
                    fqn=func_name,
                    module=ctx.module_name,
                    location=SourceLocation(
                        file_path=fp,
                        line_start=line,
                    ),
                    annotations=annotations,
                    properties=properties,
                ))
            else:
                # Indented def — check if it's inside a class
                enclosing_class = _find_enclosing_class(class_ranges, line, indent_len)
                if enclosing_class:
                    node_id = f"py:{fp}:class:{enclosing_class}:method:{func_name}"
                    properties["class"] = enclosing_class
                    result.nodes.append(GraphNode(
                        id=node_id,
                        kind=NodeKind.METHOD,
                        label=f"{enclosing_class}.{func_name}",
                        fqn=f"{enclosing_class}.{func_name}",
                        module=ctx.module_name,
                        location=SourceLocation(
                            file_path=fp,
                            line_start=line,
                        ),
                        annotations=annotations,
                        properties=properties,
                    ))
                    # DEFINES edge from class to method
                    class_node_id = f"py:{fp}:class:{enclosing_class}"
                    result.edges.append(GraphEdge(
                        source=class_node_id,
                        target=node_id,
                        kind=EdgeKind.DEFINES,
                        label=f"{enclosing_class} defines {func_name}",
                    ))

        # Imports
        for m in _IMPORT_RE.finditer(text):
            from_module = m.group(1)
            import_names = m.group(2)
            if from_module:
                result.edges.append(GraphEdge(
                    source=file_node_id,
                    target=from_module,
                    kind=EdgeKind.IMPORTS,
                    label=f"{fp} imports {from_module}",
                ))
            else:
                for name in import_names.split(","):
                    name = name.strip()
                    if name:
                        result.edges.append(GraphEdge(
                            source=file_node_id,
                            target=name,
                            kind=EdgeKind.IMPORTS,
                            label=f"{fp} imports {name}",
                        ))

        # __all__ property on a file-level module node (only if present)
        if all_exports is not None:
            result.nodes.append(GraphNode(
                id=f"py:{fp}:module",
                kind=NodeKind.MODULE,
                label=fp,
                fqn=fp,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=fp,
                    line_start=find_line_number(text, all_match.start()),
                ),
                properties={"__all__": all_exports},
            ))

        return result
