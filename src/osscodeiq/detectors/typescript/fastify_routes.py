"""Fastify route detector."""

from __future__ import annotations

import re

from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.utils import decode_text
from osscodeiq.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation


class FastifyRouteDetector:
    """Detects Fastify route definitions, plugins, and hooks."""

    name: str = "fastify_routes"
    supported_languages: tuple[str, ...] = ("typescript", "javascript")

    # fastify.get('/path', handler) or fastify.post('/path', opts, handler)
    _SHORTHAND_PATTERN = re.compile(
        r"(\w+)\.(get|post|put|delete|patch)\(\s*['\"`]([^'\"`]+)['\"`]"
    )

    # fastify.route({ method: 'GET', url: '/path' })
    _ROUTE_PATTERN = re.compile(
        r"(\w+)\.route\(\s*\{[\s\S]*?"
        r"method\s*:\s*['\"`](\w+)['\"`]"
        r"[\s\S]*?"
        r"url\s*:\s*['\"`]([^'\"`]+)['\"`]",
    )

    # fastify.register(plugin) or fastify.register(import('./plugin'))
    _REGISTER_PATTERN = re.compile(
        r"(\w+)\.register\(\s*(\w+|import\([^)]+\))"
    )

    # fastify.addHook('onRequest', handler)
    _HOOK_PATTERN = re.compile(
        r"(\w+)\.addHook\(\s*['\"`](\w+)['\"`]"
    )

    # schema: { body: SomeType } inside route options
    _SCHEMA_PATTERN = re.compile(
        r"schema\s*:\s*\{([^}]+)\}"
    )

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)

        # Detect shorthand routes: fastify.get(), .post(), etc.
        for match in self._SHORTHAND_PATTERN.finditer(text):
            method = match.group(2).upper()
            path = match.group(3)
            line = text[: match.start()].count("\n") + 1

            node_id = f"fastify:{ctx.file_path}:{method}:{path}:{line}"
            result.nodes.append(
                GraphNode(
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
                        "framework": "fastify",
                    },
                )
            )

        # Detect fastify.route({ method, url })
        for match in self._ROUTE_PATTERN.finditer(text):
            method = match.group(2).upper()
            path = match.group(3)
            line = text[: match.start()].count("\n") + 1

            node_id = f"fastify:{ctx.file_path}:{method}:{path}:{line}"
            # Avoid duplicates from shorthand detection
            if any(n.id == node_id for n in result.nodes):
                continue

            # Check for schema in the route call block (search forward from match start
            # until we find the closing ");")
            route_start = match.start()
            route_block = text[route_start:text.find(");", route_start) + 2]
            schema_props = {}
            schema_match = self._SCHEMA_PATTERN.search(route_block)
            if schema_match:
                schema_props["schema"] = schema_match.group(1).strip()

            props = {
                "protocol": "REST",
                "http_method": method,
                "path_pattern": path,
                "framework": "fastify",
            }
            props.update(schema_props)

            result.nodes.append(
                GraphNode(
                    id=node_id,
                    kind=NodeKind.ENDPOINT,
                    label=f"{method} {path}",
                    fqn=f"{ctx.file_path}::{method}:{path}",
                    module=ctx.module_name,
                    location=SourceLocation(file_path=ctx.file_path, line_start=line),
                    properties=props,
                )
            )

        # Detect fastify.register(plugin)
        for match in self._REGISTER_PATTERN.finditer(text):
            plugin_ref = match.group(2)
            line = text[: match.start()].count("\n") + 1

            edge_id_source = f"fastify:{ctx.file_path}:server:{line}"
            edge_id_target = f"fastify:{ctx.file_path}:plugin:{plugin_ref}:{line}"

            result.edges.append(
                GraphEdge(
                    source=edge_id_source,
                    target=edge_id_target,
                    kind=EdgeKind.IMPORTS,
                    label=f"register {plugin_ref}",
                    properties={
                        "framework": "fastify",
                        "plugin": plugin_ref,
                    },
                )
            )

        # Detect fastify.addHook('hookName', handler)
        for match in self._HOOK_PATTERN.finditer(text):
            hook_name = match.group(2)
            line = text[: match.start()].count("\n") + 1

            node_id = f"fastify:{ctx.file_path}:hook:{hook_name}:{line}"
            result.nodes.append(
                GraphNode(
                    id=node_id,
                    kind=NodeKind.MIDDLEWARE,
                    label=f"hook:{hook_name}",
                    fqn=f"{ctx.file_path}::hook:{hook_name}",
                    module=ctx.module_name,
                    location=SourceLocation(file_path=ctx.file_path, line_start=line),
                    properties={
                        "framework": "fastify",
                        "hook_name": hook_name,
                    },
                )
            )

        return result
