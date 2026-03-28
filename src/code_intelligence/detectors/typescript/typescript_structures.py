"""Regex-based TypeScript/JavaScript structures detector."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.utils import decode_text, find_line_number
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

_INTERFACE_RE = re.compile(r'^\s*(?:export\s+)?interface\s+(\w+)', re.MULTILINE)
_TYPE_RE = re.compile(r'^\s*(?:export\s+)?type\s+(\w+)\s*=', re.MULTILINE)
_CLASS_RE = re.compile(r'^\s*(?:export\s+)?(?:abstract\s+)?class\s+(\w+)', re.MULTILINE)
_FUNC_RE = re.compile(
    r'^\s*(?:export\s+)?(default\s+)?(?:(async)\s+)?function\s+(\w+)',
    re.MULTILINE,
)
_CONST_FUNC_RE = re.compile(
    r'^\s*(?:export\s+)?const\s+(\w+)\s*=\s*(?:(async)\s+)?\(',
    re.MULTILINE,
)
_ENUM_RE = re.compile(r'^\s*(?:export\s+)?(?:const\s+)?enum\s+(\w+)', re.MULTILINE)
_IMPORT_RE = re.compile(r'''import\s+.*?\s+from\s+['"]([^'"]+)['"]''', re.MULTILINE)
_NAMESPACE_RE = re.compile(r'^\s*(?:export\s+)?namespace\s+(\w+)', re.MULTILINE)


class TypeScriptStructuresDetector:
    """Detects TypeScript/JavaScript interfaces, types, classes, functions, enums, imports, and namespaces."""

    name: str = "typescript_structures"
    supported_languages: tuple[str, ...] = ("typescript", "javascript")

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)
        fp = ctx.file_path
        file_node_id = fp

        # Interfaces
        for m in _INTERFACE_RE.finditer(text):
            iface_name = m.group(1)
            node_id = f"ts:{fp}:interface:{iface_name}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.INTERFACE,
                label=iface_name,
                fqn=iface_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=fp,
                    line_start=find_line_number(text, m.start()),
                ),
            ))

        # Type aliases
        for m in _TYPE_RE.finditer(text):
            type_name = m.group(1)
            node_id = f"ts:{fp}:type:{type_name}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.CLASS,
                label=type_name,
                fqn=type_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=fp,
                    line_start=find_line_number(text, m.start()),
                ),
                properties={"type_alias": True},
            ))

        # Classes
        for m in _CLASS_RE.finditer(text):
            class_name = m.group(1)
            node_id = f"ts:{fp}:class:{class_name}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.CLASS,
                label=class_name,
                fqn=class_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=fp,
                    line_start=find_line_number(text, m.start()),
                ),
            ))

        # Named functions (export [default] [async] function name)
        for m in _FUNC_RE.finditer(text):
            is_default = m.group(1) is not None
            is_async = m.group(2) is not None
            func_name = m.group(3)
            node_id = f"ts:{fp}:func:{func_name}"
            properties: dict = {}
            if is_default:
                properties["default"] = True
            if is_async:
                properties["async"] = True
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.METHOD,
                label=func_name,
                fqn=func_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=fp,
                    line_start=find_line_number(text, m.start()),
                ),
                properties=properties,
            ))

        # Arrow / const functions (export const name = [async] ()
        for m in _CONST_FUNC_RE.finditer(text):
            func_name = m.group(1)
            is_async = m.group(2) is not None
            node_id = f"ts:{fp}:func:{func_name}"
            # Avoid duplicate if a named function already captured this id
            existing_ids = {n.id for n in result.nodes}
            if node_id in existing_ids:
                continue
            properties = {}
            if is_async:
                properties["async"] = True
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.METHOD,
                label=func_name,
                fqn=func_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=fp,
                    line_start=find_line_number(text, m.start()),
                ),
                properties=properties,
            ))

        # Enums
        for m in _ENUM_RE.finditer(text):
            enum_name = m.group(1)
            node_id = f"ts:{fp}:enum:{enum_name}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.ENUM,
                label=enum_name,
                fqn=enum_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=fp,
                    line_start=find_line_number(text, m.start()),
                ),
            ))

        # Imports
        for m in _IMPORT_RE.finditer(text):
            module_path = m.group(1)
            result.edges.append(GraphEdge(
                source=file_node_id,
                target=module_path,
                kind=EdgeKind.IMPORTS,
                label=f"{fp} imports {module_path}",
            ))

        # Namespaces
        for m in _NAMESPACE_RE.finditer(text):
            ns_name = m.group(1)
            node_id = f"ts:{fp}:namespace:{ns_name}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.MODULE,
                label=ns_name,
                fqn=ns_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=fp,
                    line_start=find_line_number(text, m.start()),
                ),
            ))

        return result
