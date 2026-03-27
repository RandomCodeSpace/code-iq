"""SQLAlchemy model detector."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation


class SQLAlchemyModelDetector:
    """Detects SQLAlchemy ORM models (declarative base classes)."""

    name: str = "python.sqlalchemy_models"
    supported_languages: tuple[str, ...] = ("python",)

    # class User(Base): or class User(db.Model):
    _MODEL_PATTERN = re.compile(
        r"class\s+(\w+)\(([^)]*(?:Base|Model|DeclarativeBase)[^)]*)\):"
    )

    # __tablename__ = 'users'
    _TABLE_NAME = re.compile(r"__tablename__\s*=\s*['\"](\w+)['\"]")

    # Column definitions: name = Column(String, ...) or name: Mapped[str] = mapped_column(...)
    _COLUMN_PATTERN = re.compile(
        r"(\w+)\s*(?::\s*Mapped\[.*?\])?\s*=\s*(?:Column|mapped_column)\("
    )

    # Relationship: orders = relationship("Order", ...) or orders: Mapped[list["Order"]]
    _RELATIONSHIP_PATTERN = re.compile(
        r"(\w+)\s*(?::\s*Mapped\[.*?\])?\s*=\s*relationship\(\s*['\"](\w+)['\"]"
    )

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")

        for match in self._MODEL_PATTERN.finditer(text):
            class_name = match.group(1)
            line = text[:match.start()].count("\n") + 1

            # Find the class body (rough: from class line to next class or end)
            class_start = match.start()
            next_class = re.search(r"\nclass\s+\w+", text[match.end():])
            class_body = text[class_start:match.end() + next_class.start()] if next_class else text[class_start:]

            # Extract table name
            table_match = self._TABLE_NAME.search(class_body)
            table_name = table_match.group(1) if table_match else class_name.lower() + "s"

            # Extract columns
            columns = [m.group(1) for m in self._COLUMN_PATTERN.finditer(class_body)]

            node_id = f"entity:{ctx.module_name or ''}:{class_name}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.ENTITY,
                label=class_name,
                fqn=f"{ctx.file_path}::{class_name}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                properties={
                    "table_name": table_name,
                    "columns": columns,
                    "framework": "sqlalchemy",
                },
            ))

            # Extract relationships
            for rel_match in self._RELATIONSHIP_PATTERN.finditer(class_body):
                target_class = rel_match.group(2)
                target_id = f"entity:{ctx.module_name or ''}:{target_class}"
                result.edges.append(GraphEdge(
                    source=node_id,
                    target=target_id,
                    kind=EdgeKind.MAPS_TO,
                    label=rel_match.group(1),
                ))

        return result
