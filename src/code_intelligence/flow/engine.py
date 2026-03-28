"""FlowEngine — core library for generating architecture flow diagrams.

All consumers (CLI, HTTP API, MCP tool, HTML UI) call the same methods.
FlowDiagram is the single source of truth — renderers only change format, never data.
"""

from __future__ import annotations

from code_intelligence.flow.models import FlowDiagram
from code_intelligence.flow.renderer import render_html, render_json, render_mermaid
from code_intelligence.flow.views import (
    build_auth_view,
    build_ci_view,
    build_deploy_view,
    build_overview,
    build_runtime_view,
)
from code_intelligence.graph.store import GraphStore

_VIEWS = {
    "overview": build_overview,
    "ci": build_ci_view,
    "deploy": build_deploy_view,
    "runtime": build_runtime_view,
    "auth": build_auth_view,
}

AVAILABLE_VIEWS = tuple(_VIEWS.keys())


class FlowEngine:
    """Generate and render architecture flow diagrams from a code intelligence graph.

    Usage::

        engine = FlowEngine(store)
        diagram = engine.generate("overview")
        print(engine.render(diagram, "mermaid"))
        # Or generate interactive HTML with all views:
        html = engine.render_interactive()
    """

    def __init__(self, store: GraphStore) -> None:
        self._store = store

    def generate(self, view: str = "overview") -> FlowDiagram:
        """Generate a single flow view diagram."""
        builder = _VIEWS.get(view)
        if builder is None:
            raise ValueError(f"Unknown view: {view}. Available: {', '.join(AVAILABLE_VIEWS)}")
        return builder(self._store)

    def generate_all(self) -> dict[str, FlowDiagram]:
        """Generate all views. Used for HTML interactive output."""
        return {name: self.generate(name) for name in AVAILABLE_VIEWS}

    def render(self, diagram: FlowDiagram, format: str = "mermaid") -> str:
        """Render a diagram to string.

        Args:
            diagram: The FlowDiagram to render.
            format: Output format — "mermaid" or "json".
        """
        if format == "mermaid":
            return render_mermaid(diagram)
        elif format == "json":
            return render_json(diagram)
        else:
            raise ValueError(f"Unknown format: {format}. Available: mermaid, json")

    def render_interactive(self) -> str:
        """Generate all views and bake into a self-contained interactive HTML file."""
        all_views = self.generate_all()
        stats = {
            "total_nodes": self._store.node_count,
            "total_edges": self._store.edge_count,
        }
        return render_html(all_views, stats)
