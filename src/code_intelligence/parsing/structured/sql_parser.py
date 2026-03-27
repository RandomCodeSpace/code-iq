"""SQL migration file parser using sqlparse."""

from __future__ import annotations

import re
from typing import Any

import sqlparse


class SqlParser:
    """Parses SQL migration files (Flyway, Liquibase) to extract DDL statements."""

    _TABLE_NAME_RE = re.compile(
        r"(?:CREATE|ALTER|DROP)\s+TABLE\s+(?:IF\s+(?:NOT\s+)?EXISTS\s+)?[`\"]?(\w+)[`\"]?",
        re.IGNORECASE,
    )

    def parse(self, content: bytes, file_path: str) -> dict[str, Any]:
        """Parse SQL content and extract DDL information."""
        text = content.decode("utf-8", errors="replace")
        statements = sqlparse.parse(text)

        tables_created: list[str] = []
        tables_altered: list[str] = []
        tables_dropped: list[str] = []
        raw_statements: list[str] = []

        for stmt in statements:
            stmt_text = str(stmt).strip()
            if not stmt_text:
                continue

            stmt_type = stmt.get_type()
            raw_statements.append(stmt_text)

            for match in self._TABLE_NAME_RE.finditer(stmt_text):
                table_name = match.group(1)
                upper = stmt_text.upper().lstrip()
                if upper.startswith("CREATE"):
                    tables_created.append(table_name)
                elif upper.startswith("ALTER"):
                    tables_altered.append(table_name)
                elif upper.startswith("DROP"):
                    tables_dropped.append(table_name)

        return {
            "type": "sql",
            "file": file_path,
            "tables_created": tables_created,
            "tables_altered": tables_altered,
            "tables_dropped": tables_dropped,
            "statement_count": len(raw_statements),
        }
