"""Generic multi-language import and structure detector.

Catches import/dependency patterns and structural definitions across
languages that don't have dedicated detectors.

Note: Kotlin, Rust, and Scala have been moved to dedicated detectors in
detectors/kotlin/, detectors/rust/, and detectors/scala/ respectively.
"""

from __future__ import annotations

import re
from typing import Callable

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)


def _find_line_number(text: str, pos: int) -> int:
    """Return the 1-based line number for a character offset."""
    return text[:pos].count("\n") + 1


# ---------------------------------------------------------------------------
# Ruby patterns
# ---------------------------------------------------------------------------
_RUBY_REQUIRE_RE = re.compile(r"^(?:require|require_relative)\s+'([^']+)'", re.MULTILINE)
_RUBY_MODULE_RE = re.compile(r'^\s*module\s+(\w+)', re.MULTILINE)
_RUBY_CLASS_RE = re.compile(r'^\s*class\s+(\w+)(?:\s*<\s*(\w+))?', re.MULTILINE)
_RUBY_DEF_RE = re.compile(r'^\s*def\s+(\w+)', re.MULTILINE)

# ---------------------------------------------------------------------------
# Swift patterns
# ---------------------------------------------------------------------------
_SWIFT_IMPORT_RE = re.compile(r'^\s*import\s+(\w+)', re.MULTILINE)
_SWIFT_CLASS_RE = re.compile(r'^\s*class\s+(\w+)(?:\s*:\s*([\w\s,]+))?', re.MULTILINE)
_SWIFT_PROTOCOL_RE = re.compile(r'^\s*protocol\s+(\w+)', re.MULTILINE)
_SWIFT_STRUCT_RE = re.compile(r'^\s*struct\s+(\w+)', re.MULTILINE)
_SWIFT_ENUM_RE = re.compile(r'^\s*enum\s+(\w+)', re.MULTILINE)
_SWIFT_FUNC_RE = re.compile(r'^\s*(?:override\s+)?func\s+(\w+)\s*\(', re.MULTILINE)

# ---------------------------------------------------------------------------
# Perl patterns
# ---------------------------------------------------------------------------
_PERL_PACKAGE_RE = re.compile(r'^\s*package\s+([\w:]+)\s*;', re.MULTILINE)
_PERL_SUB_RE = re.compile(r'^\s*sub\s+(\w+)', re.MULTILINE)
_PERL_USE_RE = re.compile(r'^\s*use\s+([\w:]+)', re.MULTILINE)

# ---------------------------------------------------------------------------
# Lua patterns
# ---------------------------------------------------------------------------
_LUA_REQUIRE_RE = re.compile(r"""require\s*\(\s*['"]([^'"]+)['"]\s*\)""", re.MULTILINE)
_LUA_FUNCTION_RE = re.compile(
    r'^\s*(?:local\s+)?function\s+(?:[\w.]+[.:])?(\w+)\s*\(',
    re.MULTILINE,
)

# ---------------------------------------------------------------------------
# Dart patterns
# ---------------------------------------------------------------------------
_DART_IMPORT_RE = re.compile(r"""^\s*import\s+['"]([^'"]+)['"]""", re.MULTILINE)
_DART_CLASS_RE = re.compile(
    r'^\s*(?:abstract\s+)?class\s+(\w+)'
    r'(?:\s+extends\s+(\w+))?'
    r'(?:\s+implements\s+([\w\s,]+))?',
    re.MULTILINE,
)
_DART_FUNC_RE = re.compile(r'^\s*(?:\w+\s+)?(\w+)\s*\(', re.MULTILINE)

# ---------------------------------------------------------------------------
# R patterns
# ---------------------------------------------------------------------------
_R_LIBRARY_RE = re.compile(r'(?:library|require)\s*\(\s*(\w+)\s*\)', re.MULTILINE)
_R_FUNCTION_RE = re.compile(r'^\s*(\w+)\s*<-\s*function\s*\(', re.MULTILINE)


# ---------------------------------------------------------------------------
# Language-specific detect functions
# ---------------------------------------------------------------------------

def _detect_ruby(ctx: DetectorContext, text: str, result: DetectorResult) -> None:
    file_node_id = ctx.file_path

    for m in _RUBY_REQUIRE_RE.finditer(text):
        target = m.group(1)
        result.edges.append(GraphEdge(
            source=file_node_id,
            target=target,
            kind=EdgeKind.IMPORTS,
            label=f"{ctx.file_path} imports {target}",
        ))

    for m in _RUBY_MODULE_RE.finditer(text):
        mod_name = m.group(1)
        result.nodes.append(GraphNode(
            id=f"{ctx.file_path}:{mod_name}",
            kind=NodeKind.MODULE,
            label=mod_name,
            fqn=mod_name,
            module=ctx.module_name,
            location=SourceLocation(
                file_path=ctx.file_path,
                line_start=_find_line_number(text, m.start()),
            ),
        ))

    for m in _RUBY_CLASS_RE.finditer(text):
        class_name = m.group(1)
        base_class = m.group(2)
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
            properties={"base_class": base_class} if base_class else {},
        ))
        if base_class:
            result.edges.append(GraphEdge(
                source=node_id,
                target=base_class,
                kind=EdgeKind.EXTENDS,
                label=f"{class_name} extends {base_class}",
            ))

    for m in _RUBY_DEF_RE.finditer(text):
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


def _detect_swift(ctx: DetectorContext, text: str, result: DetectorResult) -> None:
    file_node_id = ctx.file_path

    for m in _SWIFT_IMPORT_RE.finditer(text):
        target = m.group(1)
        result.edges.append(GraphEdge(
            source=file_node_id,
            target=target,
            kind=EdgeKind.IMPORTS,
            label=f"{ctx.file_path} imports {target}",
        ))

    for m in _SWIFT_CLASS_RE.finditer(text):
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
                st = st.strip()
                if st:
                    result.edges.append(GraphEdge(
                        source=node_id,
                        target=st,
                        kind=EdgeKind.EXTENDS,
                        label=f"{class_name} extends {st}",
                    ))

    for m in _SWIFT_PROTOCOL_RE.finditer(text):
        proto_name = m.group(1)
        result.nodes.append(GraphNode(
            id=f"{ctx.file_path}:{proto_name}",
            kind=NodeKind.INTERFACE,
            label=proto_name,
            fqn=proto_name,
            module=ctx.module_name,
            location=SourceLocation(
                file_path=ctx.file_path,
                line_start=_find_line_number(text, m.start()),
            ),
            properties={"type": "protocol"},
        ))

    for m in _SWIFT_STRUCT_RE.finditer(text):
        struct_name = m.group(1)
        result.nodes.append(GraphNode(
            id=f"{ctx.file_path}:{struct_name}",
            kind=NodeKind.CLASS,
            label=struct_name,
            fqn=struct_name,
            module=ctx.module_name,
            location=SourceLocation(
                file_path=ctx.file_path,
                line_start=_find_line_number(text, m.start()),
            ),
            properties={"type": "struct"},
        ))

    for m in _SWIFT_ENUM_RE.finditer(text):
        enum_name = m.group(1)
        result.nodes.append(GraphNode(
            id=f"{ctx.file_path}:{enum_name}",
            kind=NodeKind.ENUM,
            label=enum_name,
            fqn=enum_name,
            module=ctx.module_name,
            location=SourceLocation(
                file_path=ctx.file_path,
                line_start=_find_line_number(text, m.start()),
            ),
        ))

    for m in _SWIFT_FUNC_RE.finditer(text):
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


def _detect_perl(ctx: DetectorContext, text: str, result: DetectorResult) -> None:
    file_node_id = ctx.file_path

    for m in _PERL_USE_RE.finditer(text):
        target = m.group(1)
        result.edges.append(GraphEdge(
            source=file_node_id,
            target=target,
            kind=EdgeKind.IMPORTS,
            label=f"{ctx.file_path} imports {target}",
        ))

    for m in _PERL_PACKAGE_RE.finditer(text):
        pkg_name = m.group(1)
        result.nodes.append(GraphNode(
            id=f"{ctx.file_path}:{pkg_name}",
            kind=NodeKind.MODULE,
            label=pkg_name,
            fqn=pkg_name,
            module=ctx.module_name,
            location=SourceLocation(
                file_path=ctx.file_path,
                line_start=_find_line_number(text, m.start()),
            ),
        ))

    for m in _PERL_SUB_RE.finditer(text):
        sub_name = m.group(1)
        result.nodes.append(GraphNode(
            id=f"{ctx.file_path}:{sub_name}",
            kind=NodeKind.METHOD,
            label=sub_name,
            fqn=sub_name,
            module=ctx.module_name,
            location=SourceLocation(
                file_path=ctx.file_path,
                line_start=_find_line_number(text, m.start()),
            ),
        ))


def _detect_lua(ctx: DetectorContext, text: str, result: DetectorResult) -> None:
    file_node_id = ctx.file_path

    for m in _LUA_REQUIRE_RE.finditer(text):
        target = m.group(1)
        result.edges.append(GraphEdge(
            source=file_node_id,
            target=target,
            kind=EdgeKind.IMPORTS,
            label=f"{ctx.file_path} imports {target}",
        ))

    for m in _LUA_FUNCTION_RE.finditer(text):
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


def _detect_dart(ctx: DetectorContext, text: str, result: DetectorResult) -> None:
    file_node_id = ctx.file_path

    for m in _DART_IMPORT_RE.finditer(text):
        target = m.group(1)
        result.edges.append(GraphEdge(
            source=file_node_id,
            target=target,
            kind=EdgeKind.IMPORTS,
            label=f"{ctx.file_path} imports {target}",
        ))

    for m in _DART_CLASS_RE.finditer(text):
        class_name = m.group(1)
        base_class = m.group(2)
        interfaces_str = m.group(3)
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
        if interfaces_str:
            for iface in interfaces_str.split(","):
                iface = iface.strip()
                if iface:
                    result.edges.append(GraphEdge(
                        source=node_id,
                        target=iface,
                        kind=EdgeKind.IMPLEMENTS,
                        label=f"{class_name} implements {iface}",
                    ))


def _detect_r(ctx: DetectorContext, text: str, result: DetectorResult) -> None:
    file_node_id = ctx.file_path

    for m in _R_LIBRARY_RE.finditer(text):
        target = m.group(1)
        result.edges.append(GraphEdge(
            source=file_node_id,
            target=target,
            kind=EdgeKind.IMPORTS,
            label=f"{ctx.file_path} imports {target}",
        ))

    for m in _R_FUNCTION_RE.finditer(text):
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


# Dispatch table: language -> handler function
_LANGUAGE_HANDLERS: dict[str, Callable[[DetectorContext, str, DetectorResult], None]] = {
    "ruby": _detect_ruby,
    "swift": _detect_swift,
    "perl": _detect_perl,
    "lua": _detect_lua,
    "dart": _detect_dart,
    "r": _detect_r,
}


class GenericImportsDetector:
    """Detects imports, classes, and structural patterns across multiple languages."""

    name: str = "generic_imports"
    supported_languages: tuple[str, ...] = ("ruby", "swift", "perl", "lua", "dart", "r")

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        handler = _LANGUAGE_HANDLERS.get(ctx.language)
        if handler is None:
            return result

        text = ctx.content.decode("utf-8", errors="replace")
        handler(ctx, text, result)
        return result
