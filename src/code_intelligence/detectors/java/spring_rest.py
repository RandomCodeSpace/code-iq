"""Spring REST endpoint detector for Java source files."""

from __future__ import annotations

import re
from typing import Any

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.models.graph import (
    EdgeKind,
    GraphEdge,
    GraphNode,
    NodeKind,
    SourceLocation,
)

# Mapping annotations match patterns
_MAPPING_ANNOTATIONS = {
    "RequestMapping": None,  # method determined from annotation attributes
    "GetMapping": "GET",
    "PostMapping": "POST",
    "PutMapping": "PUT",
    "DeleteMapping": "DELETE",
    "PatchMapping": "PATCH",
}

_MAPPING_RE = re.compile(
    r"@(RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)"
    r"\s*(?:\(([^)]*)\))?"
)

_CLASS_RE = re.compile(r"(?:public\s+)?class\s+(\w+)")
_VALUE_RE = re.compile(r'(?:value\s*=\s*|path\s*=\s*)?\{?\s*"([^"]*)"')
_METHOD_ATTR_RE = re.compile(r'method\s*=\s*RequestMethod\.(\w+)')
_PRODUCES_RE = re.compile(r'produces\s*=\s*\{?\s*"([^"]*)"')
_CONSUMES_RE = re.compile(r'consumes\s*=\s*\{?\s*"([^"]*)"')
_JAVA_METHOD_RE = re.compile(
    r'(?:public|protected|private)?\s*(?:static\s+)?(?:[\w<>\[\],\s]+)\s+(\w+)\s*\('
)


def _extract_attr(attr_str: str | None, pattern: re.Pattern[str]) -> str | None:
    if attr_str is None:
        return None
    m = pattern.search(attr_str)
    return m.group(1) if m else None


def _find_line_number(content: bytes, byte_offset: int) -> int:
    return content[:byte_offset].count(b"\n") + 1


class SpringRestDetector:
    """Detects Spring REST endpoints from mapping annotations."""

    name: str = "spring_rest"
    supported_languages: tuple[str, ...] = ("java",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = ctx.content.decode("utf-8", errors="replace")
        lines = text.split("\n")

        # Find class name
        class_name: str | None = None
        class_base_path = ""
        for i, line in enumerate(lines):
            cm = _CLASS_RE.search(line)
            if cm:
                class_name = cm.group(1)
                # Look backwards for class-level @RequestMapping
                for j in range(max(0, i - 5), i):
                    mm = _MAPPING_RE.search(lines[j])
                    if mm and mm.group(1) == "RequestMapping":
                        path = _extract_attr(mm.group(2), _VALUE_RE)
                        if path:
                            class_base_path = path.rstrip("/")
                break

        if not class_name:
            return result

        class_node_id = f"{ctx.file_path}:{class_name}"

        # Scan for method-level mapping annotations
        for i, line in enumerate(lines):
            m = _MAPPING_RE.search(line)
            if not m:
                continue

            annotation_name = m.group(1)
            attr_str = m.group(2)

            # Skip class-level RequestMapping (already handled)
            # Heuristic: if next non-empty, non-annotation line has 'class ', skip
            is_class_level = False
            for k in range(i + 1, min(i + 5, len(lines))):
                stripped = lines[k].strip()
                if stripped.startswith("@") or not stripped:
                    continue
                if "class " in stripped or "interface " in stripped:
                    is_class_level = True
                break
            if is_class_level:
                continue

            # Determine HTTP method
            http_method = _MAPPING_ANNOTATIONS[annotation_name]
            if http_method is None:
                extracted = _extract_attr(attr_str, _METHOD_ATTR_RE)
                http_method = extracted if extracted else "GET"

            # Extract path
            path = _extract_attr(attr_str, _VALUE_RE)
            if path is None and attr_str:
                # bare string value like @GetMapping("/foo")
                bare = re.search(r'"([^"]*)"', attr_str or "")
                if bare:
                    path = bare.group(1)
            path = path or ""

            full_path = f"{class_base_path}/{path.lstrip('/')}" if path else class_base_path or "/"
            if not full_path.startswith("/"):
                full_path = "/" + full_path

            # Extract produces/consumes
            produces = _extract_attr(attr_str, _PRODUCES_RE)
            consumes = _extract_attr(attr_str, _CONSUMES_RE)

            # Find the method name on subsequent lines
            method_name = None
            for k in range(i + 1, min(i + 5, len(lines))):
                mm = _JAVA_METHOD_RE.search(lines[k])
                if mm:
                    method_name = mm.group(1)
                    break

            endpoint_label = f"{http_method} {full_path}"
            endpoint_id = f"{ctx.file_path}:{class_name}:{method_name or 'unknown'}:{http_method}:{full_path}"

            properties: dict[str, Any] = {
                "http_method": http_method,
                "path": full_path,
            }
            if produces:
                properties["produces"] = produces
            if consumes:
                properties["consumes"] = consumes

            node = GraphNode(
                id=endpoint_id,
                kind=NodeKind.ENDPOINT,
                label=endpoint_label,
                fqn=f"{class_name}.{method_name}" if method_name else class_name,
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=i + 1),
                annotations=[f"@{annotation_name}"],
                properties=properties,
            )
            result.nodes.append(node)

            edge = GraphEdge(
                source=class_node_id,
                target=endpoint_id,
                kind=EdgeKind.EXPOSES,
                label=f"{class_name} exposes {endpoint_label}",
            )
            result.edges.append(edge)

        return result
