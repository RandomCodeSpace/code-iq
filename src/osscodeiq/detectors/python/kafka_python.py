"""Kafka producer/consumer detector for Python source files.

Detects usage of confluent-kafka, aiokafka, and kafka-python libraries.
"""

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

# Producer instantiation patterns
_PRODUCER_RE = re.compile(
    r"(KafkaProducer|AIOKafkaProducer)\s*\(", re.MULTILINE
)
_CONFLUENT_PRODUCER_RE = re.compile(
    r"Producer\s*\(\s*\{", re.MULTILINE
)

# Consumer instantiation patterns
_CONSUMER_RE = re.compile(
    r"(KafkaConsumer|AIOKafkaConsumer)\s*\(", re.MULTILINE
)
_CONFLUENT_CONSUMER_RE = re.compile(
    r"Consumer\s*\(\s*\{", re.MULTILINE
)

# Topic send/produce patterns
_SEND_RE = re.compile(
    r"\.send\s*\(\s*['\"]([^'\"]+)['\"]", re.MULTILINE
)
_PRODUCE_RE = re.compile(
    r"\.produce\s*\(\s*['\"]([^'\"]+)['\"]", re.MULTILINE
)

# Subscribe pattern
_SUBSCRIBE_RE = re.compile(
    r"\.subscribe\s*\(\s*\[\s*['\"]([^'\"]+)['\"]", re.MULTILINE
)

# Import patterns
_IMPORT_RE = re.compile(
    r"(?:from|import)\s+(confluent_kafka|kafka|aiokafka)\b", re.MULTILINE
)


class KafkaPythonDetector:
    """Detects Kafka usage in Python via confluent-kafka, aiokafka, and kafka-python."""

    name: str = "kafka_python"
    supported_languages: tuple[str, ...] = ("python",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)
        lines = text.split("\n")
        fp = ctx.file_path

        # Quick bail-out: check for any Kafka-related keyword
        if not any(kw in text for kw in (
            "KafkaProducer", "KafkaConsumer",
            "AIOKafkaProducer", "AIOKafkaConsumer",
            "confluent_kafka", "from kafka",
            "import kafka", "Producer(", "Consumer(",
        )):
            return result

        seen_topics: set[str] = set()

        def _ensure_topic(topic: str, role: str, line: int) -> str:
            topic_id = f"kafka_py:{fp}:topic:{topic}"
            if topic not in seen_topics:
                seen_topics.add(topic)
                result.nodes.append(GraphNode(
                    id=topic_id,
                    kind=NodeKind.TOPIC,
                    label=f"kafka:{topic}",
                    module=ctx.module_name,
                    location=SourceLocation(file_path=fp, line_start=line),
                    properties={"broker": "kafka", "topic": topic, "role": role},
                ))
            return topic_id

        file_node_id = f"kafka_py:{fp}"

        # Detect producer instantiations
        for i, line in enumerate(lines):
            lineno = i + 1
            if _PRODUCER_RE.search(line) or _CONFLUENT_PRODUCER_RE.search(line):
                result.nodes.append(GraphNode(
                    id=f"kafka_py:{fp}:producer:{lineno}",
                    kind=NodeKind.TOPIC,
                    label="kafka:producer",
                    module=ctx.module_name,
                    location=SourceLocation(file_path=fp, line_start=lineno),
                    properties={"role": "producer"},
                ))

        # Detect consumer instantiations
        for i, line in enumerate(lines):
            lineno = i + 1
            if _CONSUMER_RE.search(line) or _CONFLUENT_CONSUMER_RE.search(line):
                result.nodes.append(GraphNode(
                    id=f"kafka_py:{fp}:consumer:{lineno}",
                    kind=NodeKind.TOPIC,
                    label="kafka:consumer",
                    module=ctx.module_name,
                    location=SourceLocation(file_path=fp, line_start=lineno),
                    properties={"role": "consumer"},
                ))

        # Detect producer.send / producer.produce -> PRODUCES edges
        for i, line in enumerate(lines):
            lineno = i + 1
            m = _SEND_RE.search(line)
            if m and ("send" in line):
                topic = m.group(1)
                topic_id = _ensure_topic(topic, "producer", lineno)
                result.edges.append(GraphEdge(
                    source=file_node_id,
                    target=topic_id,
                    kind=EdgeKind.PRODUCES,
                    label=f"produces to {topic}",
                    properties={"topic": topic},
                ))
                continue
            m = _PRODUCE_RE.search(line)
            if m:
                topic = m.group(1)
                topic_id = _ensure_topic(topic, "producer", lineno)
                result.edges.append(GraphEdge(
                    source=file_node_id,
                    target=topic_id,
                    kind=EdgeKind.PRODUCES,
                    label=f"produces to {topic}",
                    properties={"topic": topic},
                ))

        # Detect consumer.subscribe -> CONSUMES edges
        for i, line in enumerate(lines):
            lineno = i + 1
            m = _SUBSCRIBE_RE.search(line)
            if m:
                topic = m.group(1)
                topic_id = _ensure_topic(topic, "consumer", lineno)
                result.edges.append(GraphEdge(
                    source=file_node_id,
                    target=topic_id,
                    kind=EdgeKind.CONSUMES,
                    label=f"consumes from {topic}",
                    properties={"topic": topic},
                ))

        # Detect imports -> IMPORTS edges
        for i, line in enumerate(lines):
            m = _IMPORT_RE.search(line)
            if m:
                lib = m.group(1)
                result.edges.append(GraphEdge(
                    source=file_node_id,
                    target=f"kafka_py:lib:{lib}",
                    kind=EdgeKind.IMPORTS,
                    label=f"imports {lib}",
                    properties={"library": lib},
                ))

        return result
