"""YAML structured file parser with multi-document support."""

from __future__ import annotations

from typing import Any

import yaml


class YamlParser:
    """Parses YAML files into structured dictionaries."""

    def parse(self, content: bytes, file_path: str) -> dict[str, Any]:
        """Parse *content* as YAML.

        Supports multi-document YAML files (``---`` separators). When
        multiple documents are present the result contains a ``documents``
        list; single-document files return the document directly under a
        ``data`` key.
        """
        try:
            text = content.decode("utf-8", errors="replace")
            docs = list(yaml.safe_load_all(text))
        except yaml.YAMLError as exc:
            return {"error": "invalid_yaml", "file": file_path, "detail": str(exc)}

        if len(docs) == 1:
            return {
                "type": "yaml",
                "file": file_path,
                "data": docs[0],
            }

        return {
            "type": "yaml_multi",
            "file": file_path,
            "documents": docs,
        }
