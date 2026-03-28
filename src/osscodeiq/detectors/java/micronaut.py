"""Micronaut framework detector for Java source files."""

from __future__ import annotations

import re
from typing import Any

from osscodeiq.detectors.base import DetectorContext, DetectorResult
from osscodeiq.detectors.utils import decode_text
from osscodeiq.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

_CONTROLLER_RE = re.compile(r'@Controller\s*\(\s*"([^"]*)"')
_HTTP_METHOD_RE = re.compile(r'@(Get|Post|Put|Delete)(?!Mapping)\s*(?:\(\s*"([^"]*)")?\s*\)?')
_BEAN_SCOPE_RE = re.compile(r"@(Singleton|Prototype|Infrastructure)\b")
_CLIENT_RE = re.compile(r'@Client\s*\(\s*"([^"]*)"')
_INJECT_RE = re.compile(r"@Inject\b")
_SCHEDULED_RE = re.compile(r'@Scheduled\s*\(\s*fixedRate\s*=\s*"([^"]+)"')
_EVENT_LISTENER_RE = re.compile(r"@EventListener\b")
_INJECT = "@Inject"
_EVENT_LISTENER = "@EventListener"

_CLASS_RE = re.compile(r"(?:public\s+)?class\s+(\w+)")
_JAVA_METHOD_RE = re.compile(
    r"(?:public|protected|private)?\s*(?:static\s+)?(?:[\w<>\[\],\s]+)\s+(\w+)\s*\("
)


class MicronautDetector:
    """Detects Micronaut-specific patterns in Java source files."""

    name: str = "micronaut"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)

        # Fast bail
        if not any(
            marker in text
            for marker in (
                "@Controller",
                "@Get",
                "@Post",
                "@Put",
                "@Delete",
                "@Singleton",
                "@Prototype",
                "@Infrastructure",
                "@Client",
                _INJECT,
                "@Scheduled",
                _EVENT_LISTENER,
                "io.micronaut",
            )
        ):
            return result

        lines = text.split("\n")

        # Find class name and controller base path
        class_name: str | None = None
        controller_path: str | None = None
        for i, line in enumerate(lines):
            cm = _CLASS_RE.search(line)
            if cm:
                class_name = cm.group(1)
                # Look backwards for @Controller
                for j in range(max(0, i - 5), i):
                    pm = _CONTROLLER_RE.search(lines[j])
                    if pm:
                        controller_path = pm.group(1).rstrip("/")
                        break
                break

        class_node_id = f"{ctx.file_path}:{class_name}" if class_name else ctx.file_path

        # If we found a @Controller, emit a CLASS node
        if controller_path is not None and class_name:
            ctrl_id = f"micronaut:{ctx.file_path}:controller:{class_name}"
            result.nodes.append(
                GraphNode(
                    id=ctrl_id,
                    kind=NodeKind.CLASS,
                    label=f"@Controller({controller_path}) {class_name}",
                    fqn=class_name,
                    module=ctx.module_name,
                    location=SourceLocation(file_path=ctx.file_path, line_start=1),
                    annotations=["@Controller"],
                    properties={"framework": "micronaut", "path": controller_path},
                )
            )

        for i, line in enumerate(lines):
            lineno = i + 1

            # HTTP method annotations -> ENDPOINT
            m = _HTTP_METHOD_RE.search(line)
            if m:
                http_method = m.group(1).upper()
                method_path = m.group(2) or ""

                # Build full path
                if controller_path is not None:
                    full_path = f"{controller_path}/{method_path.lstrip('/')}" if method_path else controller_path
                else:
                    full_path = f"/{method_path.lstrip('/')}" if method_path else "/"
                if not full_path.startswith("/"):
                    full_path = "/" + full_path

                # Find method name
                method_name: str | None = None
                for k in range(i + 1, min(i + 5, len(lines))):
                    mm = _JAVA_METHOD_RE.search(lines[k])
                    if mm:
                        method_name = mm.group(1)
                        break

                node_id = f"micronaut:{ctx.file_path}:endpoint:{http_method}:{full_path}:{lineno}"
                endpoint_label = f"{http_method} {full_path}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.ENDPOINT,
                        label=endpoint_label,
                        fqn=f"{class_name}.{method_name}" if class_name and method_name else class_name,
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=[f"@{m.group(1)}"],
                        properties={
                            "framework": "micronaut",
                            "http_method": http_method,
                            "path": full_path,
                        },
                    )
                )
                result.edges.append(
                    GraphEdge(
                        source=class_node_id,
                        target=node_id,
                        kind=EdgeKind.EXPOSES,
                        label=f"{class_name or 'class'} exposes {endpoint_label}",
                    )
                )

            # Bean scope annotations -> MIDDLEWARE
            m = _BEAN_SCOPE_RE.search(line)
            if m:
                scope = m.group(1)
                node_id = f"micronaut:{ctx.file_path}:scope_{scope.lower()}:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.MIDDLEWARE,
                        label=f"@{scope} (bean scope)",
                        fqn=f"{class_name}.{scope}" if class_name else scope,
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=[f"@{scope}"],
                        properties={"framework": "micronaut", "bean_scope": scope},
                    )
                )

            # @Client -> DEPENDS_ON edge
            m = _CLIENT_RE.search(line)
            if m:
                client_target = m.group(1)
                client_id = f"micronaut:{ctx.file_path}:client:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=client_id,
                        kind=NodeKind.CLASS,
                        label=f"@Client({client_target})",
                        fqn=client_target,
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=["@Client"],
                        properties={"framework": "micronaut", "client_target": client_target},
                    )
                )
                result.edges.append(
                    GraphEdge(
                        source=class_node_id,
                        target=client_id,
                        kind=EdgeKind.DEPENDS_ON,
                        label=f"{class_name or 'class'} depends on {client_target}",
                    )
                )

            # @Inject -> annotation node
            m = _INJECT_RE.search(line)
            if m:
                node_id = f"micronaut:{ctx.file_path}:inject:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.MIDDLEWARE,
                        label=_INJECT,
                        fqn=f"{class_name}.inject" if class_name else "inject",
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=[_INJECT],
                        properties={"framework": "micronaut"},
                    )
                )

            # @Scheduled -> EVENT
            m = _SCHEDULED_RE.search(line)
            if m:
                rate = m.group(1)
                node_id = f"micronaut:{ctx.file_path}:scheduled:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.EVENT,
                        label=f"@Scheduled(fixedRate={rate})",
                        fqn=f"{class_name}.scheduled" if class_name else "scheduled",
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=["@Scheduled"],
                        properties={"framework": "micronaut", "fixed_rate": rate},
                    )
                )

            # @EventListener -> EVENT
            m = _EVENT_LISTENER_RE.search(line)
            if m:
                node_id = f"micronaut:{ctx.file_path}:event_listener:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.EVENT,
                        label=_EVENT_LISTENER,
                        fqn=f"{class_name}.eventListener" if class_name else "eventListener",
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=[_EVENT_LISTENER],
                        properties={"framework": "micronaut"},
                    )
                )

        return result
