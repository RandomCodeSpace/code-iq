"""NestJS controller detector."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation


class NestJSControllerDetector:
    """Detects NestJS controller decorators (@Controller, @Get, @Post, etc.)."""

    name: str = "typescript.nestjs_controllers"
    supported_languages: tuple[str, ...] = ("typescript",)

    # @Controller('users') or @Controller('/api/users')
    _CONTROLLER_PATTERN = re.compile(
        r"@Controller\(\s*['\"`]?([^'\"`)\s]*)['\"`]?\s*\)\s*\n\s*(?:export\s+)?class\s+(\w+)"
    )

    # @Get('/:id'), @Post(), @Put('/:id'), @Delete('/:id'), @Patch('/:id')
    _ROUTE_PATTERN = re.compile(
        r"@(Get|Post|Put|Delete|Patch|Options|Head)\(\s*['\"`]?([^'\"`)\s]*)['\"`]?\s*\)"
        r"\s*\n\s*(?:async\s+)?(\w+)"
    )

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")

        # Find controllers and their base paths
        controllers: dict[str, str] = {}
        for match in self._CONTROLLER_PATTERN.finditer(text):
            base_path = match.group(1) or ""
            class_name = match.group(2)
            controllers[class_name] = base_path
            line = text[:match.start()].count("\n") + 1

            class_id = f"class:{ctx.file_path}::{class_name}"
            result.nodes.append(GraphNode(
                id=class_id,
                kind=NodeKind.CLASS,
                label=class_name,
                fqn=f"{ctx.file_path}::{class_name}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                annotations=["@Controller"],
                properties={"framework": "nestjs", "stereotype": "controller"},
            ))

        # Build sorted list of (line, class_name, base_path)
        controller_ranges = []
        for match in self._CONTROLLER_PATTERN.finditer(text):
            ctrl_line = text[:match.start()].count("\n") + 1
            controller_ranges.append((ctrl_line, match.group(2), match.group(1) or ""))

        # For each route, find the controller it belongs to
        for match in self._ROUTE_PATTERN.finditer(text):
            route_line = text[:match.start()].count("\n") + 1
            # Find the last controller that starts before this route
            enclosing_ctrl = ("", "")  # (class_name, base_path)
            for ctrl_line, ctrl_name, ctrl_path in controller_ranges:
                if ctrl_line <= route_line:
                    enclosing_ctrl = (ctrl_name, ctrl_path)
            current_class, base_path = enclosing_ctrl

            method = match.group(1).upper()
            path = match.group(2) or ""
            func_name = match.group(3)

            full_path = f"/{base_path}/{path}".replace("//", "/").rstrip("/") or "/"

            node_id = f"endpoint:{ctx.module_name or ''}:{method}:{full_path}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.ENDPOINT,
                label=f"{method} {full_path}",
                fqn=f"{ctx.file_path}::{func_name}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=route_line),
                properties={
                    "protocol": "REST",
                    "http_method": method,
                    "path_pattern": full_path,
                    "framework": "nestjs",
                },
            ))

            if current_class:
                class_id = f"class:{ctx.file_path}::{current_class}"
                result.edges.append(GraphEdge(
                    source=class_id,
                    target=node_id,
                    kind=EdgeKind.EXPOSES,
                ))

        return result
