"""JSON structured file parser."""

from __future__ import annotations

import json
from typing import Any


class JsonParser:
    """Parses JSON files into structured dictionaries."""

    def parse(self, content: bytes, file_path: str) -> dict[str, Any]:
        """Parse *content* as JSON and return a structured dict."""
        try:
            text = content.decode("utf-8", errors="replace")
            data = json.loads(text)
        except (json.JSONDecodeError, UnicodeDecodeError) as exc:
            return {"error": "invalid_json", "file": file_path, "detail": str(exc)}

        return {
            "type": "json",
            "file": file_path,
            "data": data,
        }
