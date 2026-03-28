"""Regex-based Rust structures detector for Rust source files."""

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

_RUST_USE_RE = re.compile(r'^\s*use\s+([\w:]+)', re.MULTILINE)
_RUST_STRUCT_RE = re.compile(r'^\s*(?:pub\s+)?struct\s+(\w+)', re.MULTILINE)
_RUST_TRAIT_RE = re.compile(r'^\s*(?:pub\s+)?trait\s+(\w+)', re.MULTILINE)
# FIX: Match both trait impls (`impl X for Y`) and inherent impls (`impl Foo`).
# Group 1 = first type, Group 2 = second type (None for inherent impls).
_RUST_IMPL_RE = re.compile(
    r'^\s*impl(?:<[^>]*>)?\s+(\w+)(?:\s+for\s+(\w+))?\s*\{',
    re.MULTILINE,
)
# FIX: Match async fn, pub async fn, pub(crate) fn, etc.
_RUST_FN_RE = re.compile(
    r'^\s*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?:unsafe\s+)?fn\s+(\w+)\s*\(',
    re.MULTILINE,
)
_RUST_MOD_RE = re.compile(r'^\s*(?:pub\s+)?mod\s+(\w+)', re.MULTILINE)
_RUST_ENUM_RE = re.compile(r'^\s*(?:pub\s+)?enum\s+(\w+)', re.MULTILINE)
# FIX: Add macro_rules! detection.
_RUST_MACRO_RE = re.compile(r'^\s*macro_rules!\s+(\w+)', re.MULTILINE)


def _find_line_number(text: str, pos: int) -> int:
    """Return the 1-based line number for a character offset."""
    return text[:pos].count("\n") + 1


class RustStructuresDetector:
    """Detects Rust imports, structs, traits, impls, functions, enums, modules, and macros."""

    name: str = "rust_structures"
    supported_languages: tuple[str, ...] = ("rust",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        file_node_id = ctx.file_path

        for m in _RUST_USE_RE.finditer(text):
            target = m.group(1)
            result.edges.append(GraphEdge(
                source=file_node_id,
                target=target,
                kind=EdgeKind.IMPORTS,
                label=f"{ctx.file_path} imports {target}",
            ))

        for m in _RUST_MOD_RE.finditer(text):
            mod_name = m.group(1)
            result.nodes.append(GraphNode(
                id=f"{ctx.file_path}:mod:{mod_name}",
                kind=NodeKind.MODULE,
                label=mod_name,
                fqn=mod_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=_find_line_number(text, m.start()),
                ),
            ))

        for m in _RUST_STRUCT_RE.finditer(text):
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

        for m in _RUST_TRAIT_RE.finditer(text):
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

        for m in _RUST_ENUM_RE.finditer(text):
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

        # FIX: Handle both trait impls and inherent impls.
        for m in _RUST_IMPL_RE.finditer(text):
            first = m.group(1)
            second = m.group(2)
            if second:
                # Trait impl: `impl Trait for Struct`
                trait_name = first
                struct_name = second
                struct_node_id = f"{ctx.file_path}:{struct_name}"
                trait_node_id = f"{ctx.file_path}:{trait_name}"
                result.edges.append(GraphEdge(
                    source=struct_node_id,
                    target=trait_node_id,
                    kind=EdgeKind.IMPLEMENTS,
                    label=f"{struct_name} implements {trait_name}",
                ))
            else:
                # Inherent impl: `impl Foo {`
                struct_name = first
                struct_node_id = f"{ctx.file_path}:{struct_name}"
                result.edges.append(GraphEdge(
                    source=struct_node_id,
                    target=struct_node_id,
                    kind=EdgeKind.DEFINES,
                    label=f"{struct_name} inherent impl",
                ))

        for m in _RUST_FN_RE.finditer(text):
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
                properties={"type": "function"},
            ))

        # FIX: Detect macro_rules! definitions.
        for m in _RUST_MACRO_RE.finditer(text):
            macro_name = m.group(1)
            result.nodes.append(GraphNode(
                id=f"{ctx.file_path}:macro:{macro_name}",
                kind=NodeKind.METHOD,
                label=f"{macro_name}!",
                fqn=f"{macro_name}!",
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=_find_line_number(text, m.start()),
                ),
                properties={"type": "macro"},
            ))

        return result
