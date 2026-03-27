"""Java RMI detector for source files."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

_REMOTE_INTERFACE_RE = re.compile(
    r"interface\s+(\w+)\s+extends\s+(?:java\.rmi\.)?Remote"
)
_UNICAST_RE = re.compile(
    r"class\s+(\w+)\s+extends\s+(?:java\.rmi\.server\.)?UnicastRemoteObject"
)
_IMPLEMENTS_RE = re.compile(r"class\s+(\w+)\s+extends\s+\w+\s+implements\s+([\w,\s]+)")
_REGISTRY_BIND_RE = re.compile(
    r'(?:Registry|Naming)\s*\.(?:bind|rebind)\s*\(\s*"([^"]+)"'
)
_REGISTRY_LOOKUP_RE = re.compile(
    r'(?:Registry|Naming)\s*\.lookup\s*\(\s*"([^"]+)"'
)


class RmiDetector:
    """Detects Java RMI interfaces and remote object exports."""

    name: str = "rmi"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        lines = text.split("\n")

        has_remote = "Remote" in text
        has_unicast = "UnicastRemoteObject" in text
        has_naming = "Naming." in text or "Registry." in text

        if not has_remote and not has_unicast and not has_naming:
            return result

        # Detect Remote interfaces
        for i, line in enumerate(lines):
            m = _REMOTE_INTERFACE_RE.search(line)
            if m:
                iface_name = m.group(1)
                iface_id = f"{ctx.file_path}:{iface_name}"
                result.nodes.append(GraphNode(
                    id=iface_id,
                    kind=NodeKind.RMI_INTERFACE,
                    label=iface_name,
                    fqn=iface_name,
                    module=ctx.module_name,
                    location=SourceLocation(file_path=ctx.file_path, line_start=i + 1),
                    properties={"type": "remote_interface"},
                ))

        # Detect UnicastRemoteObject implementations
        for i, line in enumerate(lines):
            m = _UNICAST_RE.search(line)
            if m:
                class_name = m.group(1)
                class_id = f"{ctx.file_path}:{class_name}"

                # Find which interfaces it implements
                impl_match = _IMPLEMENTS_RE.search(line)
                implemented: list[str] = []
                if impl_match:
                    implemented = [s.strip() for s in impl_match.group(2).split(",")]

                for iface in implemented:
                    result.edges.append(GraphEdge(
                        source=class_id,
                        target=f"*:{iface}",
                        kind=EdgeKind.EXPORTS_RMI,
                        label=f"{class_name} exports {iface}",
                    ))

        # Detect registry bindings
        for i, line in enumerate(lines):
            m = _REGISTRY_BIND_RE.search(line)
            if m:
                binding_name = m.group(1)
                # Find class context
                class_name = self._find_enclosing_class(lines, i)
                if class_name:
                    class_id = f"{ctx.file_path}:{class_name}"
                    result.edges.append(GraphEdge(
                        source=class_id,
                        target=f"rmi:binding:{binding_name}",
                        kind=EdgeKind.EXPORTS_RMI,
                        label=f"{class_name} binds {binding_name}",
                        properties={"binding_name": binding_name},
                    ))

        # Detect registry lookups
        for i, line in enumerate(lines):
            m = _REGISTRY_LOOKUP_RE.search(line)
            if m:
                binding_name = m.group(1)
                class_name = self._find_enclosing_class(lines, i)
                if class_name:
                    class_id = f"{ctx.file_path}:{class_name}"
                    result.edges.append(GraphEdge(
                        source=class_id,
                        target=f"rmi:binding:{binding_name}",
                        kind=EdgeKind.INVOKES_RMI,
                        label=f"{class_name} invokes {binding_name}",
                        properties={"binding_name": binding_name},
                    ))

        return result

    @staticmethod
    def _find_enclosing_class(lines: list[str], line_idx: int) -> str | None:
        class_re = re.compile(r"class\s+(\w+)")
        for i in range(line_idx, -1, -1):
            m = class_re.search(lines[i])
            if m:
                return m.group(1)
        return None
