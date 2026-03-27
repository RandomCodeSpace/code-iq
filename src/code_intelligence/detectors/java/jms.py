"""JMS (Java Message Service) detector for Java source files."""

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

# JMS listener annotation
_JMS_LISTENER_RE = re.compile(
    r'@JmsListener\s*\(\s*(?:.*?destination\s*=\s*)?"([^"]+)"'
)

# JmsTemplate.send / convertAndSend
_JMS_SEND_RE = re.compile(
    r'(?:jmsTemplate|JmsTemplate)\s*\.(?:send|convertAndSend)\s*\(\s*"([^"]+)"'
)

# JmsTemplate with destination bean
_JMS_DEST_RE = re.compile(
    r'(?:ActiveMQQueue|ActiveMQTopic)\s*\(\s*"([^"]+)"'
)

_CONTAINER_FACTORY_RE = re.compile(r'containerFactory\s*=\s*"([^"]+)"')


class JmsDetector:
    """Detects JMS consumers and producers."""

    name: str = "jms"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        lines = text.split("\n")

        if "@JmsListener" not in text and "jmsTemplate" not in text and "JmsTemplate" not in text:
            return result

        # Find class name
        class_name: str | None = None
        for line in lines:
            cm = _CLASS_RE.search(line)
            if cm:
                class_name = cm.group(1)
                break

        if not class_name:
            return result

        class_node_id = f"{ctx.file_path}:{class_name}"
        seen_queues: set[str] = set()

        def _ensure_queue_node(destination: str) -> str:
            queue_id = f"jms:queue:{destination}"
            if destination not in seen_queues:
                seen_queues.add(destination)
                result.nodes.append(GraphNode(
                    id=queue_id,
                    kind=NodeKind.QUEUE,
                    label=f"jms:{destination}",
                    properties={"broker": "jms", "destination": destination},
                ))
            return queue_id

        # Detect @JmsListener consumers
        for i, line in enumerate(lines):
            m = _JMS_LISTENER_RE.search(line)
            if not m:
                continue

            destination = m.group(1)
            queue_id = _ensure_queue_node(destination)

            props: dict[str, str] = {"destination": destination}
            cf = _CONTAINER_FACTORY_RE.search(line)
            if cf:
                props["container_factory"] = cf.group(1)

            result.edges.append(GraphEdge(
                source=class_node_id,
                target=queue_id,
                kind=EdgeKind.CONSUMES,
                label=f"{class_name} consumes from {destination}",
                properties=props,
            ))

        # Detect JmsTemplate sends
        for i, line in enumerate(lines):
            m = _JMS_SEND_RE.search(line)
            if not m:
                continue

            destination = m.group(1)
            queue_id = _ensure_queue_node(destination)

            result.edges.append(GraphEdge(
                source=class_node_id,
                target=queue_id,
                kind=EdgeKind.PRODUCES,
                label=f"{class_name} produces to {destination}",
                properties={"destination": destination},
            ))

        return result
