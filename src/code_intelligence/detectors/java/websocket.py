"""WebSocket detector for Java source files."""

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

# JSR 356 @ServerEndpoint
_SERVER_ENDPOINT_RE = re.compile(r'@ServerEndpoint\s*\(\s*(?:value\s*=\s*)?"([^"]+)"')

# Spring WebSocket
_MESSAGE_MAPPING_RE = re.compile(r'@MessageMapping\s*\(\s*"([^"]+)"')
_SEND_TO_RE = re.compile(r'@SendTo\s*\(\s*"([^"]+)"')
_SEND_TO_USER_RE = re.compile(r'@SendToUser\s*\(\s*"([^"]+)"')

# WebSocket handler registration (in config classes)
_REGISTER_HANDLER_RE = re.compile(
    r'\.addHandler\s*\(\s*\w+\s*,\s*"([^"]+)"'
)
_STOMP_ENDPOINT_RE = re.compile(
    r'registerStompEndpoints.*?\.addEndpoint\s*\(\s*"([^"]+)"',
    re.DOTALL,
)
_APP_DEST_PREFIX_RE = re.compile(
    r'setApplicationDestinationPrefixes\s*\(\s*"([^"]+)"'
)

# SimpMessagingTemplate for sending
_MESSAGING_TEMPLATE_RE = re.compile(
    r'(?:simpMessagingTemplate|messagingTemplate)\s*\.(?:convertAndSend|convertAndSendToUser)\s*\(\s*"([^"]+)"'
)

_METHOD_RE = re.compile(r"(?:public|protected|private)?\s*(?:[\w<>\[\],?\s]+)\s+(\w+)\s*\(")


class WebSocketDetector:
    """Detects WebSocket endpoints and message handlers."""

    name: str = "websocket"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        lines = text.split("\n")

        if not any(kw in text for kw in (
            "@ServerEndpoint", "@MessageMapping", "WebSocketHandler",
            "registerStompEndpoints", "SimpMessagingTemplate",
            "simpMessagingTemplate", "messagingTemplate",
            "@SendTo", "@SendToUser",
        )):
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

        # Detect @ServerEndpoint (JSR 356)
        for m in _SERVER_ENDPOINT_RE.finditer(text):
            path = m.group(1)
            line_num = text[:m.start()].count("\n") + 1
            ws_id = f"ws:endpoint:{path}"

            result.nodes.append(GraphNode(
                id=ws_id,
                kind=NodeKind.WEBSOCKET_ENDPOINT,
                label=f"WS {path}",
                fqn=f"{class_name}:{path}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line_num),
                annotations=["@ServerEndpoint"],
                properties={"path": path, "protocol": "websocket", "type": "jsr356"},
            ))

            result.edges.append(GraphEdge(
                source=class_node_id,
                target=ws_id,
                kind=EdgeKind.EXPOSES,
                label=f"{class_name} exposes WS {path}",
            ))

        # Detect @MessageMapping (Spring STOMP)
        for i, line in enumerate(lines):
            m = _MESSAGE_MAPPING_RE.search(line)
            if not m:
                continue

            destination = m.group(1)
            method_name = None
            for k in range(i + 1, min(i + 5, len(lines))):
                mm = _METHOD_RE.search(lines[k])
                if mm:
                    method_name = mm.group(1)
                    break

            ws_id = f"ws:message:{destination}"
            result.nodes.append(GraphNode(
                id=ws_id,
                kind=NodeKind.WEBSOCKET_ENDPOINT,
                label=f"WS MSG {destination}",
                fqn=f"{class_name}.{method_name or 'unknown'}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=i + 1),
                annotations=["@MessageMapping"],
                properties={
                    "destination": destination,
                    "protocol": "websocket",
                    "type": "stomp",
                },
            ))

            result.edges.append(GraphEdge(
                source=class_node_id,
                target=ws_id,
                kind=EdgeKind.EXPOSES,
                label=f"{class_name} handles WS {destination}",
            ))

            # Check for @SendTo on next lines
            for k in range(i + 1, min(i + 5, len(lines))):
                st = _SEND_TO_RE.search(lines[k]) or _SEND_TO_USER_RE.search(lines[k])
                if st:
                    send_dest = st.group(1)
                    send_id = f"ws:topic:{send_dest}"
                    result.nodes.append(GraphNode(
                        id=send_id,
                        kind=NodeKind.WEBSOCKET_ENDPOINT,
                        label=f"WS TOPIC {send_dest}",
                        properties={"destination": send_dest, "protocol": "websocket"},
                    ))
                    result.edges.append(GraphEdge(
                        source=ws_id,
                        target=send_id,
                        kind=EdgeKind.PRODUCES,
                        label=f"{destination} sends to {send_dest}",
                    ))

        # Detect STOMP endpoint registration
        for m in _STOMP_ENDPOINT_RE.finditer(text):
            path = m.group(1)
            ws_id = f"ws:stomp:{path}"
            line_num = text[:m.start()].count("\n") + 1
            result.nodes.append(GraphNode(
                id=ws_id,
                kind=NodeKind.WEBSOCKET_ENDPOINT,
                label=f"STOMP {path}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line_num),
                properties={"path": path, "protocol": "stomp", "type": "stomp_endpoint"},
            ))

        # Detect SimpMessagingTemplate sends
        for m in _MESSAGING_TEMPLATE_RE.finditer(text):
            destination = m.group(1)
            line_num = text[:m.start()].count("\n") + 1

            result.edges.append(GraphEdge(
                source=class_node_id,
                target=f"ws:topic:{destination}",
                kind=EdgeKind.PRODUCES,
                label=f"{class_name} sends to {destination}",
                properties={"destination": destination},
            ))

        return result
