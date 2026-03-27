"""Express.js route detector."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation


class ExpressRouteDetector:
    """Detects Express.js route definitions (app.get, router.post, etc.)."""

    name: str = "typescript.express_routes"
    supported_languages: tuple[str, ...] = ("typescript", "javascript")

    # app.get('/path', handler) or router.post('/path', middleware, handler)
    _ROUTE_PATTERN = re.compile(
        r"(\w+)\.(get|post|put|delete|patch|options|head|all)\(\s*['\"`]([^'\"`]+)['\"`]"
    )

    # Router prefix: app.use('/api/v1', router) or app.use('/users', userRouter)
    _USE_PATTERN = re.compile(
        r"(\w+)\.use\(\s*['\"`]([^'\"`]+)['\"`]\s*,\s*(\w+)"
    )

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")

        for match in self._ROUTE_PATTERN.finditer(text):
            router_name = match.group(1)
            method = match.group(2).upper()
            path = match.group(3)
            line = text[:match.start()].count("\n") + 1

            node_id = f"endpoint:{ctx.module_name or ''}:{method}:{path}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.ENDPOINT,
                label=f"{method} {path}",
                fqn=f"{ctx.file_path}::{method}:{path}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                properties={
                    "protocol": "REST",
                    "http_method": method,
                    "path_pattern": path,
                    "framework": "express",
                    "router": router_name,
                },
            ))

        return result
