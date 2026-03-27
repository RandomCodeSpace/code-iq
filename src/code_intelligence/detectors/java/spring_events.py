"""Spring application events detector for Java source files."""

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
_EVENT_LISTENER_RE = re.compile(r"@EventListener")
_TRANSACTIONAL_EVENT_RE = re.compile(r"@TransactionalEventListener")
_PUBLISH_RE = re.compile(
    r"(?:applicationEventPublisher|eventPublisher|publisher)\s*\.\s*publishEvent\s*\(\s*"
    r"(?:new\s+(\w+)|(\w+))"
)
_METHOD_PARAM_RE = re.compile(r"(?:public|protected|private)?\s*\w+\s+(\w+)\s*\(\s*(\w+)\s+\w+\)")
_EVENT_CLASS_RE = re.compile(r"class\s+(\w+)\s+extends\s+(?:ApplicationEvent|AbstractEvent|\w*Event)")


class SpringEventsDetector:
    """Detects Spring event listeners and publishers."""

    name: str = "spring_events"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        lines = text.split("\n")

        has_listener = "@EventListener" in text or "@TransactionalEventListener" in text
        has_publisher = "publishEvent" in text
        has_event_class = _EVENT_CLASS_RE.search(text)

        if not has_listener and not has_publisher and not has_event_class:
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
        seen_events: set[str] = set()

        def _ensure_event_node(event_type: str) -> str:
            event_id = f"event:{event_type}"
            if event_type not in seen_events:
                seen_events.add(event_type)
                result.nodes.append(GraphNode(
                    id=event_id,
                    kind=NodeKind.EVENT,
                    label=event_type,
                    properties={"event_class": event_type},
                ))
            return event_id

        # If this file defines an event class, register it
        if has_event_class:
            event_name = has_event_class.group(1)
            _ensure_event_node(event_name)

        # Detect @EventListener / @TransactionalEventListener
        for i, line in enumerate(lines):
            if not (_EVENT_LISTENER_RE.search(line) or _TRANSACTIONAL_EVENT_RE.search(line)):
                continue

            # Find method and its parameter type (the event type)
            event_type: str | None = None
            for k in range(i + 1, min(i + 5, len(lines))):
                pm = _METHOD_PARAM_RE.search(lines[k])
                if pm:
                    event_type = pm.group(2)
                    break

            if event_type:
                event_id = _ensure_event_node(event_type)
                result.edges.append(GraphEdge(
                    source=class_node_id,
                    target=event_id,
                    kind=EdgeKind.LISTENS,
                    label=f"{class_name} listens to {event_type}",
                ))

        # Detect publishEvent calls
        for i, line in enumerate(lines):
            m = _PUBLISH_RE.search(line)
            if not m:
                continue

            event_type = m.group(1) or m.group(2)
            if event_type:
                event_id = _ensure_event_node(event_type)
                result.edges.append(GraphEdge(
                    source=class_node_id,
                    target=event_id,
                    kind=EdgeKind.PUBLISHES,
                    label=f"{class_name} publishes {event_type}",
                ))

        return result
