"""Quarkus framework detector for Java source files."""

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

_QUARKUS_TEST_RE = re.compile(r"@QuarkusTest\b")
_CONFIG_PROPERTY_RE = re.compile(r'@ConfigProperty\s*\(\s*name\s*=\s*"([^"]+)"')
_CDI_SCOPE_RE = re.compile(
    r"@(Inject|Singleton|ApplicationScoped|RequestScoped)\b"
)
_SCHEDULED_RE = re.compile(r'@Scheduled\s*\(\s*(?:every|cron)\s*=\s*"([^"]+)"')
_TRANSACTIONAL_RE = re.compile(r"@Transactional\b")
_STARTUP_RE = re.compile(r"@Startup\b")
_TRANSACTIONAL = "@Transactional"

_CLASS_RE = re.compile(r"(?:public\s+)?class\s+(\w+)")


class QuarkusDetector:
    """Detects Quarkus-specific patterns in Java source files."""

    name: str = "quarkus"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)

        # Fast bail: check for any Quarkus-related markers
        if not any(
            marker in text
            for marker in (
                "@QuarkusTest",
                "@ConfigProperty",
                "@Singleton",
                "@ApplicationScoped",
                "@RequestScoped",
                "@Scheduled",
                _TRANSACTIONAL,
                "@Startup",
                "io.quarkus",
            )
        ):
            return result

        lines = text.split("\n")

        # Find class name
        class_name: str | None = None
        for line in lines:
            cm = _CLASS_RE.search(line)
            if cm:
                class_name = cm.group(1)
                break

        for i, line in enumerate(lines):
            lineno = i + 1

            # @QuarkusTest annotation
            m = _QUARKUS_TEST_RE.search(line)
            if m:
                node_id = f"quarkus:{ctx.file_path}:quarkus_test:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.CLASS,
                        label=f"@QuarkusTest {class_name or 'unknown'}",
                        fqn=class_name,
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=["@QuarkusTest"],
                        properties={"framework": "quarkus", "test": True},
                    )
                )

            # @ConfigProperty
            m = _CONFIG_PROPERTY_RE.search(line)
            if m:
                config_key = m.group(1)
                node_id = f"quarkus:{ctx.file_path}:config_property:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.CONFIG_KEY,
                        label=f"@ConfigProperty({config_key})",
                        fqn=config_key,
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=["@ConfigProperty"],
                        properties={"framework": "quarkus", "config_key": config_key},
                    )
                )

            # CDI scope annotations
            m = _CDI_SCOPE_RE.search(line)
            if m:
                annotation = m.group(1)
                node_id = f"quarkus:{ctx.file_path}:cdi_{annotation.lower()}:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.MIDDLEWARE,
                        label=f"@{annotation} (CDI)",
                        fqn=f"{class_name}.{annotation}" if class_name else annotation,
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=[f"@{annotation}"],
                        properties={"framework": "quarkus", "cdi_scope": annotation},
                    )
                )

            # @Scheduled
            m = _SCHEDULED_RE.search(line)
            if m:
                schedule_expr = m.group(1)
                node_id = f"quarkus:{ctx.file_path}:scheduled:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.EVENT,
                        label=f"@Scheduled({schedule_expr})",
                        fqn=f"{class_name}.scheduled" if class_name else "scheduled",
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=["@Scheduled"],
                        properties={"framework": "quarkus", "schedule": schedule_expr},
                    )
                )

            # @Transactional
            m = _TRANSACTIONAL_RE.search(line)
            if m:
                node_id = f"quarkus:{ctx.file_path}:transactional:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.MIDDLEWARE,
                        label=_TRANSACTIONAL,
                        fqn=f"{class_name}.transactional" if class_name else "transactional",
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=[_TRANSACTIONAL],
                        properties={"framework": "quarkus"},
                    )
                )

            # @Startup
            m = _STARTUP_RE.search(line)
            if m:
                node_id = f"quarkus:{ctx.file_path}:startup:{lineno}"
                result.nodes.append(
                    GraphNode(
                        id=node_id,
                        kind=NodeKind.MIDDLEWARE,
                        label=f"@Startup {class_name or 'unknown'}",
                        fqn=class_name,
                        module=ctx.module_name,
                        location=SourceLocation(file_path=ctx.file_path, line_start=lineno),
                        annotations=["@Startup"],
                        properties={"framework": "quarkus"},
                    )
                )

        return result
