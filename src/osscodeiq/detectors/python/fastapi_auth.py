"""FastAPI authentication and authorization detector for Python source files."""

from __future__ import annotations

import re

from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.utils import decode_text
from osscodeiq.models.graph import GraphNode, NodeKind, SourceLocation

# Depends(get_current_user) or Depends(get_current_active_user) etc.
_DEPENDS_AUTH_RE = re.compile(
    r'Depends\(\s*(get_current[\w]*|require_auth[\w]*|auth[\w]*)\s*\)'
)

# Security(oauth2_scheme) or Security(some_auth_scheme, scopes=[...])
_SECURITY_RE = re.compile(
    r'Security\(\s*(\w+)'
)

# HTTPBearer() instantiation
_HTTP_BEARER_RE = re.compile(
    r'HTTPBearer\s*\('
)

# OAuth2PasswordBearer(tokenUrl=...) instantiation
_OAUTH2_PASSWORD_BEARER_RE = re.compile(
    r'OAuth2PasswordBearer\s*\(\s*tokenUrl\s*=\s*["\']([^"\']*)["\']'
)

# HTTPBasic() instantiation
_HTTP_BASIC_RE = re.compile(
    r'HTTPBasic\s*\('
)


def _line_number(text: str, pos: int) -> int:
    """Return 1-based line number for a character offset."""
    return text[:pos].count("\n") + 1


class FastAPIAuthDetector:
    """Detects FastAPI auth patterns in Python source files."""

    name: str = "fastapi_auth"
    supported_languages: tuple[str, ...] = ("python",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)

        # Depends(get_current_user) and similar auth dependencies
        for m in _DEPENDS_AUTH_RE.finditer(text):
            line = _line_number(text, m.start())
            dep_name = m.group(1)
            result.nodes.append(GraphNode(
                id=f"auth:{ctx.file_path}:Depends:{line}",
                kind=NodeKind.GUARD,
                label=f"Depends({dep_name})",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                annotations=[f"Depends({dep_name})"],
                properties={
                    "auth_type": "fastapi",
                    "auth_flow": "oauth2",
                    "dependency": dep_name,
                    "auth_required": True,
                },
            ))

        # Security(scheme) calls
        for m in _SECURITY_RE.finditer(text):
            line = _line_number(text, m.start())
            scheme_name = m.group(1)
            result.nodes.append(GraphNode(
                id=f"auth:{ctx.file_path}:Security:{line}",
                kind=NodeKind.GUARD,
                label=f"Security({scheme_name})",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                annotations=[f"Security({scheme_name})"],
                properties={
                    "auth_type": "fastapi",
                    "auth_flow": "oauth2",
                    "scheme": scheme_name,
                    "auth_required": True,
                },
            ))

        # HTTPBearer() instantiations
        for m in _HTTP_BEARER_RE.finditer(text):
            line = _line_number(text, m.start())
            result.nodes.append(GraphNode(
                id=f"auth:{ctx.file_path}:HTTPBearer:{line}",
                kind=NodeKind.GUARD,
                label="HTTPBearer()",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                annotations=["HTTPBearer"],
                properties={
                    "auth_type": "fastapi",
                    "auth_flow": "bearer",
                    "auth_required": True,
                },
            ))

        # OAuth2PasswordBearer(tokenUrl=...) instantiations
        for m in _OAUTH2_PASSWORD_BEARER_RE.finditer(text):
            line = _line_number(text, m.start())
            token_url = m.group(1)
            result.nodes.append(GraphNode(
                id=f"auth:{ctx.file_path}:OAuth2PasswordBearer:{line}",
                kind=NodeKind.GUARD,
                label=f"OAuth2PasswordBearer({token_url})",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                annotations=["OAuth2PasswordBearer"],
                properties={
                    "auth_type": "fastapi",
                    "auth_flow": "oauth2",
                    "token_url": token_url,
                    "auth_required": True,
                },
            ))

        # HTTPBasic() instantiations
        for m in _HTTP_BASIC_RE.finditer(text):
            line = _line_number(text, m.start())
            result.nodes.append(GraphNode(
                id=f"auth:{ctx.file_path}:HTTPBasic:{line}",
                kind=NodeKind.GUARD,
                label="HTTPBasic()",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                annotations=["HTTPBasic"],
                properties={
                    "auth_type": "fastapi",
                    "auth_flow": "basic",
                    "auth_required": True,
                },
            ))

        return result
