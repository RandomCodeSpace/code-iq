"""TypeORM / Prisma entity detector for TypeScript."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation


class TypeORMEntityDetector:
    """Detects TypeORM entity definitions (@Entity decorator)."""

    name: str = "typescript.typeorm_entities"
    supported_languages: tuple[str, ...] = ("typescript",)

    # @Entity('users') or @Entity() class User { ... }
    _ENTITY_PATTERN = re.compile(
        r"@Entity\(\s*['\"`]?(\w*)['\"`]?\s*\)\s*\n\s*(?:export\s+)?class\s+(\w+)"
    )

    # @Column() name: string;
    _COLUMN_PATTERN = re.compile(
        r"@Column\([^)]*\)\s*\n?\s*(\w+)\s*[!?]?\s*:\s*(\w+)"
    )

    # @ManyToOne, @OneToMany, @ManyToMany, @OneToOne
    _RELATION_PATTERN = re.compile(
        r"@(ManyToOne|OneToMany|ManyToMany|OneToOne)\(\s*\(\)\s*=>\s*(\w+)"
    )

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")

        for match in self._ENTITY_PATTERN.finditer(text):
            table_name = match.group(1) or match.group(2).lower() + "s"
            class_name = match.group(2)
            line = text[:match.start()].count("\n") + 1

            # Find columns in class body (rough heuristic)
            class_start = match.end()
            brace_count = 0
            class_end = len(text)
            for i, ch in enumerate(text[class_start:], class_start):
                if ch == "{":
                    brace_count += 1
                elif ch == "}":
                    brace_count -= 1
                    if brace_count == 0:
                        class_end = i
                        break
            class_body = text[class_start:class_end]

            columns = [m.group(1) for m in self._COLUMN_PATTERN.finditer(class_body)]

            node_id = f"entity:{ctx.module_name or ''}:{class_name}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.ENTITY,
                label=class_name,
                fqn=f"{ctx.file_path}::{class_name}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                annotations=["@Entity"],
                properties={
                    "table_name": table_name,
                    "columns": columns,
                    "framework": "typeorm",
                },
            ))

            # Detect relationships
            for rel_match in self._RELATION_PATTERN.finditer(class_body):
                rel_type = rel_match.group(1)
                target_entity = rel_match.group(2)
                target_id = f"entity:{ctx.module_name or ''}:{target_entity}"
                result.edges.append(GraphEdge(
                    source=node_id,
                    target=target_id,
                    kind=EdgeKind.MAPS_TO,
                    label=rel_type,
                ))

        return result
