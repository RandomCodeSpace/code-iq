"""Session, header, API key, and CSRF authentication detector."""

from __future__ import annotations

import re
from dataclasses import dataclass

from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.utils import decode_text
from osscodeiq.models.graph import GraphNode, NodeKind, SourceLocation


@dataclass(frozen=True)
class _PatternDef:
    regex: re.Pattern[str]
    auth_type: str
    node_kind: NodeKind


# -- Session patterns --
_SESSION_PATTERNS: list[_PatternDef] = [
    _PatternDef(re.compile(r"""['"]express-session['"]"""), "session", NodeKind.MIDDLEWARE),
    _PatternDef(re.compile(r"""['"]cookie-session['"]"""), "session", NodeKind.MIDDLEWARE),
    _PatternDef(re.compile(r"@SessionAttributes\b"), "session", NodeKind.GUARD),
    _PatternDef(re.compile(r"\bSessionMiddleware\b"), "session", NodeKind.MIDDLEWARE),
    _PatternDef(re.compile(r"\bHttpSession\b"), "session", NodeKind.GUARD),
    _PatternDef(re.compile(r"\bSESSION_ENGINE\b"), "session", NodeKind.GUARD),
]

# -- Header patterns --
_HEADER_PATTERNS: list[_PatternDef] = [
    _PatternDef(re.compile(r"""['"]X-API-Key['"]""", re.IGNORECASE), "header", NodeKind.GUARD),
    _PatternDef(
        re.compile(r"""(?:req|request|ctx)\.headers?\s*\[\s*['"]authorization['"]\s*\]""", re.IGNORECASE),
        "header",
        NodeKind.GUARD,
    ),
    _PatternDef(
        re.compile(r"""getHeader\s*\(\s*['"]Authorization['"]""", re.IGNORECASE),
        "header",
        NodeKind.GUARD,
    ),
]

# -- API key patterns --
_API_KEY_PATTERNS: list[_PatternDef] = [
    _PatternDef(
        re.compile(r"""(?:req|request)\.headers?\s*\[\s*['"]x-api-key['"]\s*\]""", re.IGNORECASE),
        "api_key",
        NodeKind.GUARD,
    ),
    _PatternDef(re.compile(r"\bapi[_-]?key\s*[=:]\s*", re.IGNORECASE), "api_key", NodeKind.GUARD),
    _PatternDef(re.compile(r"\bvalidate_?api_?key\b", re.IGNORECASE), "api_key", NodeKind.GUARD),
]

# -- CSRF patterns --
_CSRF_PATTERNS: list[_PatternDef] = [
    _PatternDef(re.compile(r"@csrf_protect\b"), "csrf", NodeKind.GUARD),
    _PatternDef(re.compile(r"\bcsrf_exempt\b"), "csrf", NodeKind.GUARD),
    _PatternDef(re.compile(r"\bCsrfViewMiddleware\b"), "csrf", NodeKind.MIDDLEWARE),
    _PatternDef(re.compile(r"""['"]csurf['"]"""), "csrf", NodeKind.MIDDLEWARE),
]

_ALL_PATTERNS: list[_PatternDef] = (
    _SESSION_PATTERNS + _HEADER_PATTERNS + _API_KEY_PATTERNS + _CSRF_PATTERNS
)

# Map auth_type to the ID tag used in node IDs.
_ID_TAG: dict[str, str] = {
    "session": "session",
    "header": "header",
    "api_key": "apikey",
    "csrf": "csrf",
}


class SessionHeaderAuthDetector:
    """Detects session, header, API-key, and CSRF auth patterns."""

    name: str = "session_header_auth"
    supported_languages: tuple[str, ...] = ("java", "python", "typescript")

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        if ctx.language not in self.supported_languages:
            return result

        text = decode_text(ctx)
        lines = text.split("\n")
        seen_lines: set[int] = set()

        for line_idx, line in enumerate(lines):
            for pdef in _ALL_PATTERNS:
                if line_idx in seen_lines:
                    break
                if pdef.regex.search(line):
                    seen_lines.add(line_idx)
                    line_num = line_idx + 1
                    matched_text = line.strip()
                    tag = _ID_TAG[pdef.auth_type]

                    node = GraphNode(
                        id=f"auth:{ctx.file_path}:{tag}:{line_num}",
                        kind=pdef.node_kind,
                        label=f"{pdef.auth_type} auth: {matched_text[:70]}",
                        module=ctx.module_name,
                        location=SourceLocation(
                            file_path=ctx.file_path,
                            line_start=line_num,
                            line_end=line_num,
                        ),
                        properties={
                            "auth_type": pdef.auth_type,
                            "language": ctx.language,
                            "pattern": matched_text[:120],
                        },
                    )
                    result.nodes.append(node)

        return result
