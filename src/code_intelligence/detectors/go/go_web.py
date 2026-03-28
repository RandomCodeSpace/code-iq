"""Go web framework detector for Gin, Echo, Chi, gorilla/mux, and net/http."""

from __future__ import annotations

import re

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.utils import decode_text, find_line_number
from code_intelligence.models.graph import GraphEdge, GraphNode, NodeKind, SourceLocation

# --- Route patterns ---
# Gin/Echo (uppercase methods): r.GET("/path", handler)
_UPPER_ROUTE_RE = re.compile(
    r"\.(?P<method>GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\s*\(\s*\"(?P<path>[^\"]*)\"",
    re.MULTILINE,
)
# Chi (lowercase methods): r.Get("/path", handler)
_LOWER_ROUTE_RE = re.compile(
    r"\.(?P<method>Get|Post|Put|Delete|Patch|Head|Options)\s*\(\s*\"(?P<path>[^\"]*)\"",
    re.MULTILINE,
)
# gorilla/mux: r.HandleFunc("/path", handler).Methods("GET")
_HANDLEFUNC_RE = re.compile(
    r"\.HandleFunc\s*\(\s*\"(?P<path>[^\"]*)\".*?\.Methods\s*\(\s*\"(?P<method>[A-Z]+)\"",
    re.DOTALL,
)
# gorilla/mux HandleFunc without .Methods() (default any)
_HANDLEFUNC_NO_METHOD_RE = re.compile(
    r"\.HandleFunc\s*\(\s*\"(?P<path>[^\"]*)\"",
    re.MULTILINE,
)
# net/http: http.HandleFunc("/path", handler) / http.Handle("/path", handler)
_HTTP_HANDLE_RE = re.compile(
    r"http\.(?:HandleFunc|Handle)\s*\(\s*\"(?P<path>[^\"]*)\"",
    re.MULTILINE,
)

# --- Framework detection ---
_GIN_RE = re.compile(r"gin\.(?:Default|New)\s*\(")
_ECHO_RE = re.compile(r"echo\.New\s*\(")
_CHI_RE = re.compile(r"chi\.NewRouter\s*\(")
_MUX_RE = re.compile(r"mux\.NewRouter\s*\(")

# --- Middleware ---
_USE_RE = re.compile(r"\.Use\s*\(\s*(\w+)")


def _detect_framework(text: str) -> str:
    """Determine which framework is in use based on constructor patterns."""
    if _GIN_RE.search(text):
        return "gin"
    if _ECHO_RE.search(text):
        return "echo"
    if _CHI_RE.search(text):
        return "chi"
    if _MUX_RE.search(text):
        return "mux"
    return "net_http"


class GoWebDetector:
    """Detects Go web framework route definitions across Gin, Echo, Chi, mux, and net/http."""

    name: str = "go_web"
    supported_languages: tuple[str, ...] = ("go",)

    def detect(self, ctx: DetectorContext) -> DetectorResult:
        result = DetectorResult()
        text = decode_text(ctx)

        framework = _detect_framework(text)

        # Collect (method, path, line, matched_framework) tuples
        routes: list[tuple[str, str, int, str]] = []

        # Gin/Echo uppercase routes
        for m in _UPPER_ROUTE_RE.finditer(text):
            method = m.group("method")
            path = m.group("path")
            line = find_line_number(text, m.start())
            routes.append((method, path, line, framework))

        # Chi lowercase routes
        for m in _LOWER_ROUTE_RE.finditer(text):
            method = m.group("method").upper()
            path = m.group("path")
            line = find_line_number(text, m.start())
            routes.append((method, path, line, "chi"))

        # gorilla/mux HandleFunc with .Methods()
        handlefunc_with_method_positions: set[int] = set()
        for m in _HANDLEFUNC_RE.finditer(text):
            method = m.group("method")
            path = m.group("path")
            line = find_line_number(text, m.start())
            routes.append((method, path, line, "mux"))
            handlefunc_with_method_positions.add(m.start())

        # gorilla/mux HandleFunc without .Methods() — only if mux detected
        # and not already captured with methods
        if framework == "mux":
            for m in _HANDLEFUNC_NO_METHOD_RE.finditer(text):
                if m.start() in handlefunc_with_method_positions:
                    continue
                # Check if this same position was already matched by _HANDLEFUNC_RE
                already_matched = False
                for pos in handlefunc_with_method_positions:
                    if pos == m.start():
                        already_matched = True
                        break
                if not already_matched:
                    path = m.group("path")
                    line = find_line_number(text, m.start())
                    routes.append(("ANY", path, line, "mux"))

        # net/http Handle/HandleFunc
        for m in _HTTP_HANDLE_RE.finditer(text):
            path = m.group("path")
            line = find_line_number(text, m.start())
            routes.append(("ANY", path, line, "net_http"))

        # Emit ENDPOINT nodes
        for method, path, line, fw in routes:
            node_id = f"go_web:{ctx.file_path}:{method}:{path}:{line}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.ENDPOINT,
                label=f"{method} {path}",
                fqn=f"{ctx.file_path}::{method}:{path}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                properties={
                    "framework": fw,
                    "http_method": method,
                    "path": path,
                },
            ))

        # Middleware nodes
        for m in _USE_RE.finditer(text):
            mw_name = m.group(1)
            line = find_line_number(text, m.start())
            node_id = f"go_web:{ctx.file_path}:middleware:{mw_name}:{line}"
            result.nodes.append(GraphNode(
                id=node_id,
                kind=NodeKind.MIDDLEWARE,
                label=mw_name,
                fqn=f"{ctx.file_path}::middleware:{mw_name}",
                module=ctx.module_name,
                location=SourceLocation(file_path=ctx.file_path, line_start=line),
                properties={
                    "framework": framework,
                    "middleware": mw_name,
                },
            ))

        return result
