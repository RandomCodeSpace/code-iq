"""FastAPI route detector."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation


class FastAPIRouteDetector:
    """Detects FastAPI route decorators (@app.get, @router.post, etc.)."""

    name: str = "python.fastapi_routes"
    supported_languages: tuple[str, ...] = ("python",)

    _ROUTE_PATTERN = re.compile(
        r"@(\w+)\.(get|post|put|delete|patch|options|head)\(\s*['\"]([^'\"]+)['\"]"
        r".*?\)\s*\n(?:\s*async\s+)?def\s+(\w+)",
        re.DOTALL,
    )

    # APIRouter prefix: router = APIRouter(prefix="/api/v1/users")
    _ROUTER_PREFIX = re.compile(
        r"(\w+)\s*=\s*APIRouter\(.*?prefix\s*=\s*['\"]([^'\"]+)['\"]",
        re.DOTALL,
    )

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")

        # Extract router prefixes
        prefixes: dict[str, str] = {}
        for match in self._ROUTER_PREFIX.finditer(text):
            prefixes[match.group(1)] = match.group(2)

        for match in self._ROUTE_PATTERN.finditer(text):
            router_name = match.group(1)
            method = match.group(2).upper()
            path = match.group(3)
            func_name = match.group(4)

            # Prepend router prefix if known
            prefix = prefixes.get(router_name, "")
            full_path = prefix + path

            line = text[:match.start()].count("\n") + 1

            node_id = f"endpoint:{ctx.module_name or ''}:{method}:{full_path}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.ENDPOINT,
                label=f"{method} {full_path}",
                fqn=f"{ctx.file_path}::{func_name}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                properties={
                    "protocol": "REST",
                    "http_method": method,
                    "path_pattern": full_path,
                    "framework": "fastapi",
                    "router": router_name,
                },
            ))

        return result
