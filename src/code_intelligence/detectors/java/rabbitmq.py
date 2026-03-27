"""RabbitMQ detector for Java source files."""

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

# @RabbitListener
_RABBIT_LISTENER_RE = re.compile(
    r'@RabbitListener\s*\(\s*(?:.*?queues?\s*=\s*)?[\{"]?\s*"([^"]+)"'
)

# @RabbitHandler
_RABBIT_HANDLER_RE = re.compile(r"@RabbitHandler")

# RabbitTemplate.convertAndSend / send
_RABBIT_SEND_RE = re.compile(
    r'(?:rabbitTemplate|RabbitTemplate)\s*\.(?:convertAndSend|send)\s*\(\s*"([^"]+)"'
)

# Exchange/queue/binding declarations
_EXCHANGE_RE = re.compile(
    r'(?:DirectExchange|TopicExchange|FanoutExchange|HeadersExchange)\s*\(\s*"([^"]+)"'
)
_QUEUE_DECL_RE = re.compile(r'Queue\s*\(\s*"([^"]+)"')
_BINDING_RE = re.compile(
    r'BindingBuilder\s*\.bind\s*\(\s*(\w+)\s*\)\s*\.to\s*\(\s*(\w+)\s*\)'
)

_ROUTING_KEY_RE = re.compile(r'routingKey\s*=\s*"([^"]+)"')


class RabbitmqDetector:
    """Detects RabbitMQ consumers and producers."""

    name: str = "rabbitmq"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        lines = text.split("\n")

        if not any(kw in text for kw in (
            "@RabbitListener", "RabbitTemplate", "rabbitTemplate",
            "DirectExchange", "TopicExchange", "FanoutExchange",
        )):
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

        def _ensure_queue_node(queue: str) -> str:
            queue_id = f"rabbitmq:queue:{queue}"
            if queue not in seen_queues:
                seen_queues.add(queue)
                result.nodes.append(GraphNode(
                    id=queue_id,
                    kind=NodeKind.QUEUE,
                    label=f"rabbitmq:{queue}",
                    properties={"broker": "rabbitmq", "queue": queue},
                ))
            return queue_id

        # Detect @RabbitListener consumers
        for i, line in enumerate(lines):
            m = _RABBIT_LISTENER_RE.search(line)
            if not m:
                continue

            queue = m.group(1)
            queue_id = _ensure_queue_node(queue)

            result.edges.append(GraphEdge(
                source=class_node_id,
                target=queue_id,
                kind=EdgeKind.CONSUMES,
                label=f"{class_name} consumes from {queue}",
                properties={"queue": queue},
            ))

        # Detect RabbitTemplate sends
        for i, line in enumerate(lines):
            m = _RABBIT_SEND_RE.search(line)
            if not m:
                continue

            exchange_or_queue = m.group(1)
            routing_key = _ROUTING_KEY_RE.search(line)

            props: dict[str, str] = {"exchange": exchange_or_queue}
            if routing_key:
                props["routing_key"] = routing_key.group(1)

            queue_id = f"rabbitmq:exchange:{exchange_or_queue}"
            if exchange_or_queue not in seen_queues:
                seen_queues.add(exchange_or_queue)
                result.nodes.append(GraphNode(
                    id=queue_id,
                    kind=NodeKind.QUEUE,
                    label=f"rabbitmq:{exchange_or_queue}",
                    properties={"broker": "rabbitmq", "exchange": exchange_or_queue},
                ))

            result.edges.append(GraphEdge(
                source=class_node_id,
                target=queue_id,
                kind=EdgeKind.PRODUCES,
                label=f"{class_name} produces to {exchange_or_queue}",
                properties=props,
            ))

        # Detect exchange declarations
        for m in _EXCHANGE_RE.finditer(text):
            exchange_name = m.group(1)
            line_num = text[:m.start()].count("\n") + 1
            exchange_id = f"rabbitmq:exchange:{exchange_name}"
            if exchange_name not in seen_queues:
                seen_queues.add(exchange_name)
                result.nodes.append(GraphNode(
                    id=exchange_id,
                    kind=NodeKind.QUEUE,
                    label=f"rabbitmq:exchange:{exchange_name}",
                    location=SourceLocation(file_path=ctx.file_path, line_start=line_num),
                    properties={"broker": "rabbitmq", "exchange": exchange_name},
                ))

        return result
