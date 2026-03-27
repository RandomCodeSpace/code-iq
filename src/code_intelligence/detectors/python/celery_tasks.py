"""Celery task detector."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind, SourceLocation


class CeleryTaskDetector:
    """Detects Celery task definitions and task invocations."""

    name: str = "python.celery_tasks"
    supported_languages: tuple[str, ...] = ("python",)

    # @app.task or @shared_task or @celery.task
    _TASK_DECORATOR = re.compile(
        r"@(?:\w+\.)?(?:task|shared_task)\(?"
        r"(?:.*?name\s*=\s*['\"]([^'\"]+)['\"])?"
        r"[^)]*\)?\s*\n\s*def\s+(\w+)",
        re.DOTALL,
    )

    # task.delay(...) or task.apply_async(...)
    _TASK_CALL = re.compile(
        r"(\w+)\.(delay|apply_async|s|si|signature)\("
    )

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")

        # Detect task definitions (these are like queue consumers)
        for match in self._TASK_DECORATOR.finditer(text):
            task_name = match.group(1) or match.group(2)
            func_name = match.group(2)
            line = text[:match.start()].count("\n") + 1

            # Create a queue node for the task
            queue_id = f"queue:{ctx.module_name or ''}:celery:{task_name}"
            result.nodes.append(GraphNode(
                id=queue_id,
                kind=NodeKind.QUEUE,
                label=f"celery:{task_name}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                properties={
                    "broker": "celery",
                    "task_name": task_name,
                    "function": func_name,
                },
            ))

            # The task function consumes from this queue
            method_id = f"method:{ctx.file_path}::{func_name}"
            result.nodes.append(GraphNode(
                id=method_id,
                kind=NodeKind.METHOD,
                label=func_name,
                fqn=f"{ctx.file_path}::{func_name}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
            ))
            result.edges.append(GraphEdge(
                source=method_id,
                target=queue_id,
                kind=EdgeKind.CONSUMES,
                label="celery_task",
            ))

        # Detect task invocations (producers)
        for match in self._TASK_CALL.finditer(text):
            task_ref = match.group(1)
            call_type = match.group(2)
            line = text[:match.start()].count("\n") + 1

            queue_id = f"queue:{ctx.module_name or ''}:celery:{task_ref}"
            caller_id = f"method:{ctx.file_path}::caller_l{line}"
            result.edges.append(GraphEdge(
                source=caller_id,
                target=queue_id,
                kind=EdgeKind.PRODUCES,
                label=f"{task_ref}.{call_type}",
            ))

        return result
