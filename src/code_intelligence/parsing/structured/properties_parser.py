"""Java .properties file parser."""

from __future__ import annotations

from typing import Any


class PropertiesParser:
    """Parses Java-style ``.properties`` files into structured dicts."""

    def parse(self, content: bytes, file_path: str) -> dict[str, Any]:
        """Parse key=value property entries.

        Handles ``=`` and ``:`` separators, comment lines (``#``, ``!``),
        blank lines, and continuation lines ending with ``\\``.
        """
        text = content.decode("utf-8", errors="replace")
        properties: dict[str, str] = {}

        logical_line = ""
        for raw_line in text.splitlines():
            # Handle continuation lines
            if logical_line:
                raw_line = raw_line.lstrip()
            logical_line += raw_line

            if logical_line.endswith("\\"):
                logical_line = logical_line[:-1]
                continue

            line = logical_line.strip()
            logical_line = ""

            if not line or line.startswith("#") or line.startswith("!"):
                continue

            # Split on first unescaped = or :
            sep_idx = -1
            for i, ch in enumerate(line):
                if ch in ("=", ":") and (i == 0 or line[i - 1] != "\\"):
                    sep_idx = i
                    break

            if sep_idx == -1:
                # Treat the whole line as a key with empty value
                properties[line] = ""
            else:
                key = line[:sep_idx].rstrip()
                value = line[sep_idx + 1 :].lstrip()
                properties[key] = value

        return {
            "type": "properties",
            "file": file_path,
            "data": properties,
        }
