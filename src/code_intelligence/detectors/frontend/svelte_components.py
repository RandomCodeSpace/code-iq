"""Svelte component detector."""

from __future__ import annotations

import re
from pathlib import PurePosixPath

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.utils import decode_text
from code_intelligence.models.graph import GraphNode, NodeKind, SourceLocation


class SvelteComponentDetector:
    """Detects Svelte component patterns: props, reactive statements, script blocks."""

    name: str = "frontend.svelte_components"
    supported_languages: tuple[str, ...] = ("typescript", "javascript", "svelte")

    # export let propName  or  export let propName = defaultValue
    _PROP_PATTERN = re.compile(r"export\s+let\s+(\w+)")

    # $: reactive statement
    _REACTIVE_PATTERN = re.compile(r"^\s*\$:", re.MULTILINE)

    # Detect Svelte script blocks (not used for HTML sanitization — structural detection only)
    _SCRIPT_PATTERN = re.compile(r"^<script\b", re.MULTILINE)  # nosec

    # Detect template content — any HTML tag that isn't script/style
    _HTML_TEMPLATE_PATTERN = re.compile(r"^<(?!script\b|style\b|/)[a-zA-Z]\w*[\s>]", re.MULTILINE)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)

        has_props = bool(self._PROP_PATTERN.search(text))
        has_reactive = bool(self._REACTIVE_PATTERN.search(text))
        has_script = bool(self._SCRIPT_PATTERN.search(text))
        has_template = bool(self._HTML_TEMPLATE_PATTERN.search(text))

        # A file is a Svelte component if it has export let (props) or reactive
        # statements, or if it has both a <script> block and HTML template content.
        is_svelte = has_props or has_reactive or (has_script and has_template)

        if not is_svelte:
            return result

        # Derive component name from filename (without extension)
        component_name = PurePosixPath(ctx.file_path).stem

        # Collect props
        props: list[str] = []
        for match in self._PROP_PATTERN.finditer(text):
            props.append(match.group(1))

        # Count reactive statements
        reactive_count = len(self._REACTIVE_PATTERN.findall(text))

        # Find the first significant line for location
        first_line = 1
        for pattern in (self._SCRIPT_PATTERN, self._PROP_PATTERN, self._REACTIVE_PATTERN):
            m = pattern.search(text)
            if m:
                candidate = text[: m.start()].count("\n") + 1
                first_line = min(first_line, candidate) if first_line > 1 else candidate
                break

        node_id = f"svelte:{ctx.file_path}:component:{component_name}"
        result.nodes.append(
            GraphNode(
                id=node_id,
                kind=NodeKind.COMPONENT,
                label=component_name,
                fqn=f"{ctx.file_path}::{component_name}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=first_line),
                properties={
                    "framework": "svelte",
                    "props": props,
                    "reactive_statements": reactive_count,
                },
            )
        )

        return result
