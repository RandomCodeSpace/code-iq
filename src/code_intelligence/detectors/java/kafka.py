"""Kafka producer/consumer detector for Java source files."""

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
_KAFKA_LISTENER_RE = re.compile(
    r'@KafkaListener\s*\(\s*(?:.*?topics?\s*=\s*)?[\{"]?\s*"([^"]+)"'
)
_KAFKA_SEND_RE = re.compile(
    r'(?:kafkaTemplate|KafkaTemplate)\s*\.send\s*\(\s*"([^"]+)"'
)
_KAFKA_SEND_CONST_RE = re.compile(
    r'(?:kafkaTemplate|KafkaTemplate)\s*\.send\s*\(\s*(\w+)'
)
_GROUP_ID_RE = re.compile(r'groupId\s*=\s*"([^"]+)"')


class KafkaDetector:
    """Detects Kafka consumers (@KafkaListener) and producers (KafkaTemplate.send)."""

    name: str = "kafka"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        lines = text.split("\n")

        if "KafkaListener" not in text and "KafkaTemplate" not in text and "kafkaTemplate" not in text:
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
        seen_topics: set[str] = set()

        def _ensure_topic_node(topic: str) -> str:
            topic_id = f"kafka:topic:{topic}"
            if topic not in seen_topics:
                seen_topics.add(topic)
                result.nodes.append(GraphNode(
                    id=topic_id,
                    kind=NodeKind.TOPIC,
                    label=f"kafka:{topic}",
                    properties={"broker": "kafka", "topic": topic},
                ))
            return topic_id

        # Detect @KafkaListener consumers
        for i, line in enumerate(lines):
            m = _KAFKA_LISTENER_RE.search(line)
            if not m:
                # Multi-line annotation — check if previous line has @KafkaListener
                if i > 0 and "@KafkaListener" in lines[i - 1]:
                    m = re.search(r'"([^"]+)"', line)
                if not m:
                    continue

            topic = m.group(1)
            topic_id = _ensure_topic_node(topic)

            group_id = _GROUP_ID_RE.search(line)
            props: dict[str, str] = {"topic": topic}
            if group_id:
                props["group_id"] = group_id.group(1)

            result.edges.append(GraphEdge(
                source=class_node_id,
                target=topic_id,
                kind=EdgeKind.CONSUMES,
                label=f"{class_name} consumes {topic}",
                properties=props,
            ))

        # Detect KafkaTemplate.send producers
        for i, line in enumerate(lines):
            m = _KAFKA_SEND_RE.search(line)
            if not m:
                continue

            topic = m.group(1)
            topic_id = _ensure_topic_node(topic)

            result.edges.append(GraphEdge(
                source=class_node_id,
                target=topic_id,
                kind=EdgeKind.PRODUCES,
                label=f"{class_name} produces to {topic}",
                properties={"topic": topic},
            ))

        return result
