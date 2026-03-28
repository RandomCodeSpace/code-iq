"""Regex-based Entity Framework Core detector for C# source files."""

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

_DBCONTEXT_RE = re.compile(r'class\s+(\w+)\s*:\s*(?:[\w.]+\.)?DbContext', re.MULTILINE)
_DBSET_RE = re.compile(r'DbSet<(\w+)>', re.MULTILINE)
_KEY_RE = re.compile(r'\[Key\]')
_FK_RE = re.compile(r'\[ForeignKey\("(\w+)"\)\]')
_TABLE_RE = re.compile(r'\[Table\("(\w+)"\)\]')
_FLUENT_RE = re.compile(r'\.(HasOne|HasMany|WithMany|WithOne)\s*\(', re.MULTILINE)
_MIGRATION_RE = re.compile(r'class\s+(\w+)\s*:\s*Migration', re.MULTILINE)
_CREATE_TABLE_RE = re.compile(r'CreateTable\s*\(\s*(?:name:\s*)?"(\w+)"', re.MULTILINE)


class CSharpEfcoreDetector:
    """Detects Entity Framework Core patterns: DbContext, DbSet, annotations, fluent API, and migrations."""

    name: str = "csharp_efcore"
    supported_languages: tuple[str, ...] = ("csharp",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)

        context_ids: list[str] = []

        # DbContext classes
        for m in _DBCONTEXT_RE.finditer(text):
            context_name = m.group(1)
            node_id = f"efcore:{ctx.file_path}:context:{context_name}"
            context_ids.append(node_id)
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.REPOSITORY,
                label=context_name,
                fqn=context_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=find_line_number(text, m.start()),
                ),
                properties={"framework": "efcore"},
            ))

        # DbSet properties -> ENTITY nodes + QUERIES edges from context
        for m in _DBSET_RE.finditer(text):
            entity_name = m.group(1)
            entity_id = f"efcore:{ctx.file_path}:entity:{entity_name}"
            line_num = find_line_number(text, m.start())
            result.nodes.append(GraphNode(
                id=entity_id,
                kind=NodeKind.ENTITY,
                label=entity_name,
                fqn=entity_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=line_num,
                ),
                properties={"framework": "efcore"},
            ))
            # Link each context to this entity
            for ctx_id in context_ids:
                result.edges.append(GraphEdge(
                    source=ctx_id,
                    target=entity_id,
                    kind=EdgeKind.QUERIES,
                    label=f"{ctx_id} queries {entity_name}",
                ))

        # [Table("tablename")] annotation -> property on nearest entity
        for m in _TABLE_RE.finditer(text):
            table_name = m.group(1)
            line_num = find_line_number(text, m.start())
            # Find the nearest DbSet entity declared after this annotation
            # or create an entity node with table_name property
            nearest_entity = _find_nearest_entity(result, ctx.file_path, line_num)
            if nearest_entity:
                nearest_entity.properties["table_name"] = table_name

        # [Key] annotation
        for m in _KEY_RE.finditer(text):
            line_num = find_line_number(text, m.start())
            nearest_entity = _find_nearest_entity(result, ctx.file_path, line_num)
            if nearest_entity:
                if "annotations" not in nearest_entity.properties:
                    nearest_entity.properties["annotations"] = []
                if "Key" not in nearest_entity.properties["annotations"]:
                    nearest_entity.properties["annotations"].append("Key")

        # [ForeignKey("Name")] -> DEPENDS_ON edge
        for m in _FK_RE.finditer(text):
            fk_target = m.group(1)
            line_num = find_line_number(text, m.start())
            nearest_entity = _find_nearest_entity(result, ctx.file_path, line_num)
            if nearest_entity:
                result.edges.append(GraphEdge(
                    source=nearest_entity.id,
                    target=f"efcore:*:entity:{fk_target}",
                    kind=EdgeKind.DEPENDS_ON,
                    label=f"{nearest_entity.label} depends on {fk_target}",
                ))

        # Fluent API relationship methods -> DEPENDS_ON edges
        for m in _FLUENT_RE.finditer(text):
            method_name = m.group(1)
            line_num = find_line_number(text, m.start())
            # Link from the context to signal a relationship
            for ctx_id in context_ids:
                result.edges.append(GraphEdge(
                    source=ctx_id,
                    target=f"efcore:{ctx.file_path}:fluent:{method_name}:{line_num}",
                    kind=EdgeKind.DEPENDS_ON,
                    label=f"{method_name} relationship",
                    properties={"fluent_method": method_name},
                ))

        # Migration classes
        for m in _MIGRATION_RE.finditer(text):
            migration_name = m.group(1)
            migration_id = f"efcore:{ctx.file_path}:migration:{migration_name}"
            result.nodes.append(GraphNode(
                id=migration_id,
                kind=NodeKind.MIGRATION,
                label=migration_name,
                fqn=migration_name,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=find_line_number(text, m.start()),
                ),
                properties={"framework": "efcore"},
            ))

        # migrationBuilder.CreateTable("name") -> ENTITY node
        for m in _CREATE_TABLE_RE.finditer(text):
            table_name = m.group(1)
            entity_id = f"efcore:{ctx.file_path}:entity:{table_name}"
            # Avoid duplicates
            if not any(n.id == entity_id for n in result.nodes):
                result.nodes.append(GraphNode(
                    id=entity_id,
                    kind=NodeKind.ENTITY,
                    label=table_name,
                    fqn=table_name,
                    module=ctx.module_name,
                    location=SourceLocation(
                        file_path=ctx.file_path,
                        line_start=find_line_number(text, m.start()),
                    ),
                    properties={"framework": "efcore", "source": "migration"},
                ))

        return result


def _find_nearest_entity(result: DetectorResult, file_path: str, line_num: int) -> GraphNode | None:
    """Find the nearest ENTITY node at or after the given line in the same file."""
    candidates = [
        n for n in result.nodes
        if n.kind == NodeKind.ENTITY
        and n.location is not None
        and n.location.file_path == file_path
    ]
    if not candidates:
        return None
    # Find the entity whose line_start is closest to (and >= ) line_num
    after = [n for n in candidates if n.location and n.location.line_start and n.location.line_start >= line_num]
    if after:
        return min(after, key=lambda n: n.location.line_start)  # type: ignore[union-attr, return-value]
    # Fallback: nearest entity before line_num
    return max(candidates, key=lambda n: n.location.line_start if n.location and n.location.line_start else 0)
