"""Regex-based .NET 6+ Minimal API detector for C# source files."""

from __future__ import annotations

import re

from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.utils import decode_text, find_line_number
from osscodeiq.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

_MAP_RE = re.compile(r'\.Map(Get|Post|Put|Delete|Patch)\s*\(\s*"([^"]*)"', re.MULTILINE)
_BUILDER_RE = re.compile(r'WebApplication\.CreateBuilder\s*\(', re.MULTILINE)
_AUTH_USE_RE = re.compile(r'\.Use(Authentication|Authorization)\s*\(', re.MULTILINE)
_AUTH_ADD_RE = re.compile(r'\.Add(Authentication|Authorization)\s*\(', re.MULTILINE)
_DI_RE = re.compile(r'\.Add(Scoped|Transient|Singleton)<(\w+)(?:,\s*(\w+))?>', re.MULTILINE)


class CSharpMinimalApisDetector:
    """Detects .NET 6+ Minimal API patterns: endpoints, auth middleware, and DI registration."""

    name: str = "csharp_minimal_apis"
    supported_languages: tuple[str, ...] = ("csharp",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)

        app_module_id: str | None = None

        # WebApplication.CreateBuilder() -> MODULE node
        builder_match = _BUILDER_RE.search(text)
        if builder_match:
            app_module_id = f"dotnet:{ctx.file_path}:app"
            line_num = find_line_number(text, builder_match.start())
            result.nodes.append(GraphNode(
                id=app_module_id,
                kind=NodeKind.MODULE,
                label=f"WebApplication({ctx.file_path})",
                fqn=ctx.file_path,
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=line_num,
                ),
                properties={"framework": "dotnet_minimal_api"},
            ))

        # Map{Method}("/path", handler) -> ENDPOINT nodes
        for m in _MAP_RE.finditer(text):
            http_method = m.group(1).upper()
            path = m.group(2)
            line_num = find_line_number(text, m.start())
            endpoint_id = f"dotnet:{ctx.file_path}:endpoint:{http_method}:{path}:{line_num}"
            result.nodes.append(GraphNode(
                id=endpoint_id,
                kind=NodeKind.ENDPOINT,
                label=f"{http_method} {path}",
                fqn=f"{http_method} {path}",
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=line_num,
                ),
                properties={
                    "http_method": http_method,
                    "path": path,
                    "framework": "dotnet_minimal_api",
                },
            ))
            # Link endpoint to app module if present
            if app_module_id:
                result.edges.append(GraphEdge(
                    source=app_module_id,
                    target=endpoint_id,
                    kind=EdgeKind.EXPOSES,
                    label=f"app exposes {http_method} {path}",
                ))

        # UseAuthentication / UseAuthorization -> GUARD nodes
        for m in _AUTH_USE_RE.finditer(text):
            auth_type = m.group(1)
            line_num = find_line_number(text, m.start())
            guard_id = f"dotnet:{ctx.file_path}:guard:Use{auth_type}:{line_num}"
            result.nodes.append(GraphNode(
                id=guard_id,
                kind=NodeKind.GUARD,
                label=f"Use{auth_type}",
                fqn=f"Use{auth_type}",
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=line_num,
                ),
                properties={
                    "guard_type": auth_type.lower(),
                    "framework": "dotnet_minimal_api",
                },
            ))

        # AddAuthentication / AddAuthorization -> GUARD nodes
        for m in _AUTH_ADD_RE.finditer(text):
            auth_type = m.group(1)
            line_num = find_line_number(text, m.start())
            guard_id = f"dotnet:{ctx.file_path}:guard:Add{auth_type}:{line_num}"
            result.nodes.append(GraphNode(
                id=guard_id,
                kind=NodeKind.GUARD,
                label=f"Add{auth_type}",
                fqn=f"Add{auth_type}",
                module=ctx.module_name,
                location=SourceLocation(
                    file_path=ctx.file_path,
                    line_start=line_num,
                ),
                properties={
                    "guard_type": auth_type.lower(),
                    "framework": "dotnet_minimal_api",
                },
            ))

        # DI registration: AddScoped<IService, ServiceImpl>() -> DEPENDS_ON edge
        for m in _DI_RE.finditer(text):
            lifetime = m.group(1)
            interface_name = m.group(2)
            impl_name = m.group(3)  # May be None for single-type registrations
            if impl_name:
                result.edges.append(GraphEdge(
                    source=f"dotnet:*:{impl_name}",
                    target=f"dotnet:*:{interface_name}",
                    kind=EdgeKind.DEPENDS_ON,
                    label=f"{impl_name} registered as {interface_name} ({lifetime})",
                    properties={
                        "lifetime": lifetime.lower(),
                        "framework": "dotnet_minimal_api",
                    },
                ))
            else:
                # Self-registration like AddScoped<MyService>()
                result.edges.append(GraphEdge(
                    source=f"dotnet:{ctx.file_path}:di:{interface_name}",
                    target=f"dotnet:*:{interface_name}",
                    kind=EdgeKind.DEPENDS_ON,
                    label=f"{interface_name} registered as {lifetime}",
                    properties={
                        "lifetime": lifetime.lower(),
                        "framework": "dotnet_minimal_api",
                    },
                ))

        return result
