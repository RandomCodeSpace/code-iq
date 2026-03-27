"""Raw SQL query detector for Java source files."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import (
    GraphNode,
    NodeKind,
    SourceLocation,
)

_CLASS_RE = re.compile(r"(?:public\s+)?class\s+(\w+)")

# @Query annotations with SQL/JPQL
_QUERY_ANNO_RE = re.compile(
    r'@Query\s*\(\s*(?:value\s*=\s*)?"((?:[^"\\]|\\.)+)"', re.DOTALL
)
_NATIVE_QUERY_RE = re.compile(r"nativeQuery\s*=\s*true")

# JdbcTemplate patterns
_JDBC_TEMPLATE_RE = re.compile(
    r'(?:jdbcTemplate|namedParameterJdbcTemplate|JdbcTemplate)\s*\.'
    r'(?:query|queryForObject|queryForList|queryForMap|update|execute|batchUpdate)'
    r'\s*\(\s*"((?:[^"\\]|\\.)+)"',
    re.DOTALL,
)

# EntityManager.createNativeQuery / createQuery
_EM_QUERY_RE = re.compile(
    r'(?:entityManager|em)\s*\.(?:createNativeQuery|createQuery)\s*\(\s*"((?:[^"\\]|\\.)+)"',
    re.DOTALL,
)

# SQL table references
_TABLE_REF_RE = re.compile(
    r'\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\s+(\w+)', re.IGNORECASE
)


class RawSqlDetector:
    """Detects raw SQL queries in @Query annotations and JdbcTemplate calls."""

    name: str = "raw_sql"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        lines = text.split("\n")

        if "@Query" not in text and "jdbcTemplate" not in text and "JdbcTemplate" not in text and "createNativeQuery" not in text and "createQuery" not in text:
            return result

        # Find class name
        class_name: str | None = None
        for line in lines:
            cm = _CLASS_RE.search(line)
            if cm:
                class_name = cm.group(1)
                break

        class_name = class_name or "Unknown"

        # Detect @Query annotations
        for m in _QUERY_ANNO_RE.finditer(text):
            query_str = m.group(1)
            line_num = text[:m.start()].count("\n") + 1
            is_native = bool(_NATIVE_QUERY_RE.search(text[m.start():m.end() + 50]))

            tables = _TABLE_REF_RE.findall(query_str)

            query_id = f"{ctx.file_path}:{class_name}:query:L{line_num}"
            result.nodes.append(GraphNode(
                id=query_id,
                kind=NodeKind.QUERY,
                label=query_str[:80] + ("..." if len(query_str) > 80 else ""),
                fqn=f"{class_name}.query@L{line_num}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line_num),
                annotations=["@Query"],
                properties={
                    "query": query_str,
                    "native": is_native,
                    "source": "annotation",
                    "tables": tables,
                },
            ))

        # Detect JdbcTemplate queries
        for m in _JDBC_TEMPLATE_RE.finditer(text):
            query_str = m.group(1)
            line_num = text[:m.start()].count("\n") + 1
            tables = _TABLE_REF_RE.findall(query_str)

            query_id = f"{ctx.file_path}:{class_name}:jdbc:L{line_num}"
            result.nodes.append(GraphNode(
                id=query_id,
                kind=NodeKind.QUERY,
                label=query_str[:80] + ("..." if len(query_str) > 80 else ""),
                fqn=f"{class_name}.jdbc@L{line_num}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line_num),
                properties={
                    "query": query_str,
                    "native": True,
                    "source": "jdbc_template",
                    "tables": tables,
                },
            ))

        # Detect EntityManager queries
        for m in _EM_QUERY_RE.finditer(text):
            query_str = m.group(1)
            line_num = text[:m.start()].count("\n") + 1
            tables = _TABLE_REF_RE.findall(query_str)

            query_id = f"{ctx.file_path}:{class_name}:em:L{line_num}"
            result.nodes.append(GraphNode(
                id=query_id,
                kind=NodeKind.QUERY,
                label=query_str[:80] + ("..." if len(query_str) > 80 else ""),
                fqn=f"{class_name}.em@L{line_num}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line_num),
                properties={
                    "query": query_str,
                    "native": "createNativeQuery" in text[max(0, m.start() - 30):m.start() + 20],
                    "source": "entity_manager",
                    "tables": tables,
                },
            ))

        return result
