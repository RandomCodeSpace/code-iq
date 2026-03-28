"""Go ORM/database detector for GORM, sqlx, and database/sql."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.utils import decode_text, find_line_number
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation

# --- GORM patterns ---
# gorm.Model embedding in struct: type User struct { gorm.Model ... }
_GORM_MODEL_RE = re.compile(
    r"type\s+(?P<name>\w+)\s+struct\s*\{[^}]*gorm\.Model",
    re.DOTALL,
)
# db.AutoMigrate(&User{}, &Product{})
_GORM_MIGRATE_RE = re.compile(
    r"\.AutoMigrate\s*\(",
    re.MULTILINE,
)
# GORM query operations: db.Create(, db.Find(, db.Where(, db.First(, db.Save(, db.Delete(
_GORM_QUERY_RE = re.compile(
    r"\.(?P<op>Create|Find|Where|First|Save|Delete)\s*\(",
    re.MULTILINE,
)

# --- sqlx patterns ---
# sqlx.Connect( / sqlx.Open(
_SQLX_CONNECT_RE = re.compile(
    r"sqlx\.(?P<op>Connect|Open)\s*\(",
    re.MULTILINE,
)
# sqlx query operations: db.Select(, db.Get(, db.NamedExec(
_SQLX_QUERY_RE = re.compile(
    r"\.(?P<op>Select|Get|NamedExec)\s*\(",
    re.MULTILINE,
)

# --- database/sql patterns ---
# sql.Open(
_SQL_OPEN_RE = re.compile(
    r"sql\.Open\s*\(",
    re.MULTILINE,
)
# db.Query(, db.QueryRow(, db.Exec(
_SQL_QUERY_RE = re.compile(
    r"\.(?P<op>Query|QueryRow|Exec)\s*\(",
    re.MULTILINE,
)

# --- Framework detection ---
_HAS_GORM_RE = re.compile(r"\"gorm\.io/")
_HAS_SQLX_RE = re.compile(r"\"github\.com/jmoiron/sqlx\"")
_HAS_DATABASE_SQL_RE = re.compile(r"\"database/sql\"")


def _detect_orm(text: str) -> str | None:
    """Determine which ORM/database library is in use."""
    if _HAS_GORM_RE.search(text):
        return "gorm"
    if _HAS_SQLX_RE.search(text):
        return "sqlx"
    if _HAS_DATABASE_SQL_RE.search(text):
        return "database_sql"
    return None


class GoOrmDetector:
    """Detects Go ORM and database patterns for GORM, sqlx, and database/sql."""

    name: str = "go_orm"
    supported_languages: tuple[str, ...] = ("go",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)

        orm = _detect_orm(text)

        # --- GORM entity models ---
        for m in _GORM_MODEL_RE.finditer(text):
            model_name = m.group("name")
            line = find_line_number(text, m.start())
            node_id = f"go_orm:{ctx.file_path}:entity:{model_name}:{line}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.ENTITY,
                label=model_name,
                fqn=f"{ctx.file_path}::{model_name}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                properties={
                    "framework": "gorm",
                    "type": "model",
                },
            ))

        # --- GORM migrations ---
        for m in _GORM_MIGRATE_RE.finditer(text):
            line = find_line_number(text, m.start())
            node_id = f"go_orm:{ctx.file_path}:migration:{line}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.MIGRATION,
                label="AutoMigrate",
                fqn=f"{ctx.file_path}::AutoMigrate",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                properties={
                    "framework": "gorm",
                    "type": "auto_migrate",
                },
            ))

        # --- GORM queries ---
        if orm == "gorm":
            for m in _GORM_QUERY_RE.finditer(text):
                op = m.group("op")
                line = find_line_number(text, m.start())
                source_id = f"go_orm:{ctx.file_path}:query:{op}:{line}"
                result.edges.append(GraphEdge(
                    source=ctx.file_path,
                    target=source_id,
                    kind=EdgeKind.QUERIES,
                    label=f"gorm.{op}",
                    properties={
                        "framework": "gorm",
                        "operation": op,
                    },
                ))

        # --- sqlx connections ---
        for m in _SQLX_CONNECT_RE.finditer(text):
            op = m.group("op")
            line = find_line_number(text, m.start())
            node_id = f"go_orm:{ctx.file_path}:connection:sqlx:{line}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.DATABASE_CONNECTION,
                label=f"sqlx.{op}",
                fqn=f"{ctx.file_path}::sqlx.{op}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                properties={
                    "framework": "sqlx",
                    "operation": op,
                },
            ))

        # --- sqlx queries ---
        if orm == "sqlx":
            for m in _SQLX_QUERY_RE.finditer(text):
                op = m.group("op")
                line = find_line_number(text, m.start())
                target_id = f"go_orm:{ctx.file_path}:query:sqlx:{op}:{line}"
                result.edges.append(GraphEdge(
                    source=ctx.file_path,
                    target=target_id,
                    kind=EdgeKind.QUERIES,
                    label=f"sqlx.{op}",
                    properties={
                        "framework": "sqlx",
                        "operation": op,
                    },
                ))

        # --- database/sql connections ---
        for m in _SQL_OPEN_RE.finditer(text):
            line = find_line_number(text, m.start())
            node_id = f"go_orm:{ctx.file_path}:connection:sql:{line}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.DATABASE_CONNECTION,
                label="sql.Open",
                fqn=f"{ctx.file_path}::sql.Open",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                properties={
                    "framework": "database_sql",
                    "operation": "Open",
                },
            ))

        # --- database/sql queries ---
        if orm == "database_sql":
            for m in _SQL_QUERY_RE.finditer(text):
                op = m.group("op")
                line = find_line_number(text, m.start())
                target_id = f"go_orm:{ctx.file_path}:query:sql:{op}:{line}"
                result.edges.append(GraphEdge(
                    source=ctx.file_path,
                    target=target_id,
                    kind=EdgeKind.QUERIES,
                    label=f"sql.{op}",
                    properties={
                        "framework": "database_sql",
                        "operation": op,
                    },
                ))

        return result
