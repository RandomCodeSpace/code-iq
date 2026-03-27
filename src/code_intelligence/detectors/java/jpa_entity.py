"""JPA entity detector for Java source files."""

from __future__ import annotations

import re
from typing import Any

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

_ENTITY_RE = re.compile(r"@Entity")
_TABLE_RE = re.compile(r'@Table\s*\(\s*(?:name\s*=\s*)?"(\w+)"')
_CLASS_RE = re.compile(r"(?:public\s+)?class\s+(\w+)")
_COLUMN_RE = re.compile(r'@Column\s*\(([^)]*)\)')
_COLUMN_NAME_RE = re.compile(r'name\s*=\s*"(\w+)"')
_FIELD_RE = re.compile(r"(?:private|protected|public)\s+([\w<>,\s]+)\s+(\w+)\s*[;=]")

_RELATIONSHIP_ANNOTATIONS = {
    "OneToMany": "one_to_many",
    "ManyToOne": "many_to_one",
    "OneToOne": "one_to_one",
    "ManyToMany": "many_to_many",
}
_RELATIONSHIP_RE = re.compile(r"@(OneToMany|ManyToOne|OneToOne|ManyToMany)")
_TARGET_ENTITY_RE = re.compile(r'targetEntity\s*=\s*(\w+)\.class')
_MAPPED_BY_RE = re.compile(r'mappedBy\s*=\s*"(\w+)"')
_GENERIC_TYPE_RE = re.compile(r'<(\w+)>')


class JpaEntityDetector:
    """Detects JPA entities and their relationships."""

    name: str = "jpa_entity"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        lines = text.split("\n")

        # Check if file contains @Entity
        if not _ENTITY_RE.search(text):
            return result

        # Find class name
        class_name: str | None = None
        class_line: int = 0
        for i, line in enumerate(lines):
            cm = _CLASS_RE.search(line)
            if cm:
                class_name = cm.group(1)
                class_line = i + 1
                break

        if not class_name:
            return result

        # Extract table name
        table_match = _TABLE_RE.search(text)
        table_name = table_match.group(1) if table_match else class_name.lower()

        # Extract columns
        columns: list[dict[str, str]] = []
        for i, line in enumerate(lines):
            col_match = _COLUMN_RE.search(line)
            if col_match:
                col_name_match = _COLUMN_NAME_RE.search(col_match.group(1))
                # Find the field on the next line(s)
                for k in range(i + 1, min(i + 3, len(lines))):
                    fm = _FIELD_RE.search(lines[k])
                    if fm:
                        col_name = col_name_match.group(1) if col_name_match else fm.group(2)
                        columns.append({"name": col_name, "field": fm.group(2), "type": fm.group(1).strip()})
                        break

        entity_id = f"{ctx.file_path}:{class_name}"
        properties: dict[str, Any] = {"table_name": table_name}
        if columns:
            properties["columns"] = columns

        node = GraphNode(
            id=entity_id,
            kind=NodeKind.ENTITY,
            label=f"{class_name} ({table_name})",
            fqn=class_name,
            module=ctx.module_name,
            location=SourceLocation(file_path=ctx.file_path, line_start=class_line),
            annotations=["@Entity"],
            properties=properties,
        )
        result.nodes.append(node)

        # Extract relationships
        for i, line in enumerate(lines):
            rel_match = _RELATIONSHIP_RE.search(line)
            if not rel_match:
                continue

            rel_type = _RELATIONSHIP_ANNOTATIONS[rel_match.group(1)]

            # Determine target entity
            target_entity: str | None = None
            # Check for targetEntity attribute
            target_match = _TARGET_ENTITY_RE.search(line)
            if target_match:
                target_entity = target_match.group(1)
            else:
                # Look at field type on subsequent lines
                for k in range(i + 1, min(i + 4, len(lines))):
                    fm = _FIELD_RE.search(lines[k])
                    if fm:
                        field_type = fm.group(1).strip()
                        gm = _GENERIC_TYPE_RE.search(field_type)
                        if gm:
                            target_entity = gm.group(1)
                        else:
                            # Direct type reference (e.g., ManyToOne Address)
                            target_entity = field_type.split()[-1] if field_type else None
                        break

            if target_entity:
                mapped_by = _MAPPED_BY_RE.search(line)
                edge_props: dict[str, Any] = {"relationship_type": rel_type}
                if mapped_by:
                    edge_props["mapped_by"] = mapped_by.group(1)

                edge = GraphEdge(
                    source=entity_id,
                    target=f"*:{target_entity}",  # Wildcard target resolved later
                    kind=EdgeKind.MAPS_TO,
                    label=f"{class_name} {rel_type} {target_entity}",
                    properties=edge_props,
                )
                result.edges.append(edge)

        return result
