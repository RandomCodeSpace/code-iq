"""gRPC service detector for Java source files."""

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

_CLASS_RE = re.compile(r"(?:public\s+)?class\s+(\w+)")

# gRPC service implementation pattern: extends XxxGrpc.XxxImplBase
_GRPC_IMPL_RE = re.compile(
    r"class\s+(\w+)\s+extends\s+(\w+)Grpc\.(\w+)ImplBase"
)

# @GrpcService annotation (grpc-spring-boot-starter)
_GRPC_SERVICE_ANNO_RE = re.compile(r"@GrpcService")

# Override methods in gRPC service
_OVERRIDE_METHOD_RE = re.compile(
    r"@Override\s+\n?\s*public\s+void\s+(\w+)\s*\("
)

# gRPC channel/stub usage for client detection
_GRPC_STUB_RE = re.compile(
    r"(\w+)Grpc\.new(?:Blocking|Future|)Stub\s*\("
)
_GRPC_CHANNEL_RE = re.compile(
    r'ManagedChannelBuilder\s*\.forAddress\s*\(\s*"([^"]+)"\s*,\s*(\d+)'
)

# Method override pattern (simpler)
_METHOD_RE = re.compile(
    r"(?:public)\s+(?:void|[\w<>\[\]]+)\s+(\w+)\s*\(\s*(\w+)"
)


class GrpcServiceDetector:
    """Detects gRPC service implementations and client stubs."""

    name: str = "grpc_service"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        lines = text.split("\n")

        has_grpc_impl = "ImplBase" in text or "@GrpcService" in text
        has_grpc_stub = "Grpc.new" in text

        if not has_grpc_impl and not has_grpc_stub:
            return result

        # Find class name
        class_name: str | None = None
        class_line: int = 0
        for i, line in enumerate(lines):
            cm = _CLASS_RE.search(line)
            if cm:
                class_name = cm.group(1)
                class_line = i + 1
                break

        if not class_name:
            return result

        class_node_id = f"{ctx.file_path}:{class_name}"

        # Detect gRPC service implementation
        impl_match = _GRPC_IMPL_RE.search(text)
        if impl_match:
            service_proto = impl_match.group(2)  # The proto service name
            service_id = f"grpc:service:{service_proto}"

            result.nodes.append(GraphNode(
                id=service_id,
                kind=NodeKind.ENDPOINT,
                label=f"gRPC {service_proto}",
                fqn=f"{class_name} ({service_proto})",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=class_line),
                annotations=["@GrpcService"] if "@GrpcService" in text else [],
                properties={
                    "protocol": "grpc",
                    "service": service_proto,
                    "implementation": class_name,
                },
            ))

            result.edges.append(GraphEdge(
                source=class_node_id,
                target=service_id,
                kind=EdgeKind.EXPOSES,
                label=f"{class_name} implements gRPC {service_proto}",
            ))

            # Find RPC methods (overridden methods)
            for i, line in enumerate(lines):
                if "@Override" in line:
                    for k in range(i + 1, min(i + 3, len(lines))):
                        mm = _METHOD_RE.search(lines[k])
                        if mm:
                            method_name = mm.group(1)
                            rpc_id = f"grpc:rpc:{service_proto}/{method_name}"
                            result.nodes.append(GraphNode(
                                id=rpc_id,
                                kind=NodeKind.ENDPOINT,
                                label=f"gRPC {service_proto}/{method_name}",
                                fqn=f"{class_name}.{method_name}",
                                module=ctx.module_name,
                                location=SourceLocation(file_path=ctx.file_path, line_start=k + 1),
                                properties={
                                    "protocol": "grpc",
                                    "service": service_proto,
                                    "method": method_name,
                                },
                            ))
                            break

        # Detect gRPC client stubs
        for m in _GRPC_STUB_RE.finditer(text):
            target_service = m.group(1)
            line_num = text[:m.start()].count("\n") + 1

            result.edges.append(GraphEdge(
                source=class_node_id,
                target=f"grpc:service:{target_service}",
                kind=EdgeKind.CALLS,
                label=f"{class_name} calls gRPC {target_service}",
                properties={"protocol": "grpc", "target_service": target_service},
            ))

        return result
