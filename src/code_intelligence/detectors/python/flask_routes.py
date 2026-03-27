"""Flask route detector."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation


class FlaskRouteDetector:
    """Detects Flask route decorators (@app.route, @blueprint.route)."""

    name: str = "python.flask_routes"
    supported_languages: tuple[str, ...] = ("python",)

    # Matches @app.route('/path', methods=['GET', 'POST']) and @blueprint.route(...)
    _ROUTE_PATTERN = re.compile(
        r"@(\w+)\.(route)\(\s*['\"]([^'\"]+)['\"]"
        r"(?:.*?methods\s*=\s*\[([^\]]+)\])?"
        r".*?\)\s*\n\s*def\s+(\w+)",
        re.DOTALL,
    )

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")

        for match in self._ROUTE_PATTERN.finditer(text):
            blueprint = match.group(1)
            path = match.group(3)
            methods_raw = match.group(4)
            func_name = match.group(5)

            methods = ["GET"]
            if methods_raw:
                methods = [m.strip().strip("'\"") for m in methods_raw.split(",")]

            line = text[:match.start()].count("\n") + 1

            for method in methods:
                node_id = f"endpoint:{ctx.module_name or ''}:{method}:{path}"
                result.nodes.append(GraphNode(
                    id=node_id,
                    kind=NodeKind.ENDPOINT,
                    label=f"{method} {path}",
                    fqn=f"{ctx.file_path}::{func_name}",
                    module=ctx.module_name,
                    location=SourceLocation(file_path=ctx.file_path, line_start=line),
                    properties={
                        "protocol": "REST",
                        "http_method": method,
                        "path_pattern": path,
                        "framework": "flask",
                        "blueprint": blueprint,
                    },
                ))

                class_id = f"class:{ctx.file_path}::{blueprint}"
                result.edges.append(GraphEdge(
                    source=class_id,
                    target=node_id,
                    kind=EdgeKind.EXPOSES,
                ))

        return result
