"""Django URL pattern and view detector."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation


class DjangoViewDetector:
    """Detects Django URL patterns, class-based views, and function views."""

    name: str = "python.django_views"
    supported_languages: tuple[str, ...] = ("python",)

    # urlpatterns entries: path('api/users/', UserView.as_view(), name='user-list')
    _URL_PATTERN = re.compile(
        r"(?:path|re_path|url)\(\s*['\"]([^'\"]+)['\"]"
        r"\s*,\s*(\w[\w.]*)"
    )

    # Class-based views: class UserView(APIView): or class UserViewSet(ModelViewSet):
    _CBV_PATTERN = re.compile(
        r"class\s+(\w+)\(([^)]*(?:View|ViewSet|Mixin)[^)]*)\):"
    )

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")

        # Detect URL patterns (typically in urls.py)
        if "urlpatterns" in text:
            for match in self._URL_PATTERN.finditer(text):
                path_pattern = match.group(1)
                view_ref = match.group(2)
                line = text[:match.start()].count("\n") + 1

                node_id = f"endpoint:{ctx.module_name or ''}:ALL:{path_pattern}"
                result.nodes.append(GraphNode(
                    id=node_id,
                    kind=NodeKind.ENDPOINT,
                    label=f"{path_pattern}",
                    fqn=view_ref,
                    module=ctx.module_name,
                    location=SourceLocation(file_path=ctx.file_path, line_start=line),
                    properties={
                        "protocol": "REST",
                        "path_pattern": path_pattern,
                        "framework": "django",
                        "view_reference": view_ref,
                    },
                ))

        # Detect class-based views
        for match in self._CBV_PATTERN.finditer(text):
            class_name = match.group(1)
            bases = match.group(2)
            line = text[:match.start()].count("\n") + 1

            node_id = f"class:{ctx.file_path}::{class_name}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.CLASS,
                label=class_name,
                fqn=f"{ctx.file_path}::{class_name}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                annotations=[f"extends:{bases.strip()}"],
                properties={"framework": "django", "stereotype": "view"},
            ))

        return result
