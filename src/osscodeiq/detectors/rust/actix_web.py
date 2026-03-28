"""Actix-web and Axum web framework detector for Rust source files."""

from __future__ import annotations

import re

from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.utils import decode_text
from osscodeiq.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

# Actix attribute macros: #[get("/path")], #[post("/path")], etc.
_ACTIX_ATTR_RE = re.compile(r'#\[(get|post|put|delete)\s*\(\s*"([^"]*)"\s*\)\s*\]')

# HttpServer::new(
_HTTP_SERVER_RE = re.compile(r"HttpServer::new\s*\(")

# .route("/path", web::get().to(handler))
_ROUTE_RE = re.compile(r'\.route\s*\(\s*"([^"]*)"\s*,\s*web::(get|post|put|delete)\s*\(\s*\)\s*\.to\s*\(\s*(\w+)')

# .service(web::resource("/path"))
_SERVICE_RESOURCE_RE = re.compile(r'\.service\s*\(\s*web::resource\s*\(\s*"([^"]*)"')

# Axum: Router::new().route("/path", get(handler))
_AXUM_ROUTE_RE = re.compile(r'\.route\s*\(\s*"([^"]*)"\s*,\s*(get|post|put|delete)\s*\(\s*(\w+)\s*\)')

# Axum: .layer(middleware)
_AXUM_LAYER_RE = re.compile(r"\.layer\s*\(\s*(\w+)")

# #[actix_web::main] or #[tokio::main]
_MAIN_ATTR_RE = re.compile(r"#\[(actix_web::main|tokio::main)\]")

# fn function_name
_FN_RE = re.compile(r"(?:pub\s+)?(?:async\s+)?fn\s+(\w+)")


class ActixWebDetector:
    """Detects Actix-web and Axum web framework patterns in Rust source files."""

    name: str = "actix_web"
    supported_languages: tuple[str, ...] = ("rust",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)

        # Fast bail
        if not any(
            marker in text
            for marker in (
                "#[get",
                "#[post",
                "#[put",
                "#[delete",
                "HttpServer::new",
                "web::get",
                "web::post",
                "web::resource",
                "Router::new",
                ".layer(",
                "actix_web::main",
                "tokio::main",
                "actix_web",
                "axum",
            )
        ):
            return result

        lines = text.split("\n")

        for i, line in enumerate(lines):
            lineno = i + 1

            # Actix attribute macros
            m = _ACTIX_ATTR_RE.search(line)
            if m:
                method = m.group(1).upper()
                path = m.group(2)

                # Find the function name on subsequent lines
                fn_name: str | None = None
                for k in range(i + 1, min(i + 5, len(lines))):
                    fm = _FN_RE.search(lines[k])
                    if fm:
                        fn_name = fm.group(1)
                        break

                node_id = f"rust_web:{ctx.file_path}:{method}:{path}:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.ENDPOINT,
                        label=f"{method} {path}",
                        fqn=fn_name,
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=[f"#[{m.group(1)}]"],
                        properties={
                            "framework": "actix_web",
                            "http_method": method,
                            "path": path,
                        },
                    )
                )

            # HttpServer::new
            m = _HTTP_SERVER_RE.search(line)
            if m:
                node_id = f"rust_web:{ctx.file_path}:http_server:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.MODULE,
                        label="HttpServer",
                        fqn="HttpServer",
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=[],
                        properties={"framework": "actix_web"},
                    )
                )

            # .route("/path", web::get().to(handler))
            m = _ROUTE_RE.search(line)
            if m:
                path = m.group(1)
                method = m.group(2).upper()
                handler = m.group(3)
                node_id = f"rust_web:{ctx.file_path}:{method}:{path}:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.ENDPOINT,
                        label=f"{method} {path}",
                        fqn=handler,
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=[],
                        properties={
                            "framework": "actix_web",
                            "http_method": method,
                            "path": path,
                            "handler": handler,
                        },
                    )
                )

            # .service(web::resource("/path"))
            m = _SERVICE_RESOURCE_RE.search(line)
            if m:
                path = m.group(1)
                node_id = f"rust_web:{ctx.file_path}:resource:{path}:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.ENDPOINT,
                        label=f"resource {path}",
                        fqn=path,
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=[],
                        properties={"framework": "actix_web", "path": path},
                    )
                )

            # Axum Router::new().route("/path", get(handler))
            m = _AXUM_ROUTE_RE.search(line)
            if m:
                path = m.group(1)
                method = m.group(2).upper()
                handler = m.group(3)
                node_id = f"rust_web:{ctx.file_path}:{method}:{path}:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.ENDPOINT,
                        label=f"{method} {path}",
                        fqn=handler,
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=[],
                        properties={
                            "framework": "axum",
                            "http_method": method,
                            "path": path,
                            "handler": handler,
                        },
                    )
                )

            # Axum .layer(middleware)
            m = _AXUM_LAYER_RE.search(line)
            if m:
                middleware_name = m.group(1)
                node_id = f"rust_web:{ctx.file_path}:layer:{middleware_name}:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.MIDDLEWARE,
                        label=f"layer({middleware_name})",
                        fqn=middleware_name,
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=[],
                        properties={"framework": "axum", "middleware": middleware_name},
                    )
                )

            # #[actix_web::main] / #[tokio::main]
            m = _MAIN_ATTR_RE.search(line)
            if m:
                attr = m.group(1)
                node_id = f"rust_web:{ctx.file_path}:main:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.MODULE,
                        label=f"#[{attr}]",
                        fqn="main",
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=[f"#[{attr}]"],
                        properties={
                            "framework": "actix_web" if "actix" in attr else "tokio",
                        },
                    )
                )

        return result
