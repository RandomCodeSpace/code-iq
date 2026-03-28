"""Data models for flow diagrams — the single source of truth for all renderers."""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class FlowNode:
    """A node in a flow diagram (collapsed/summarized from graph nodes)."""
    id: str
    label: str
    kind: str               # "trigger", "job", "service", "endpoint", "database", "guard", etc.
    properties: dict = field(default_factory=dict)
    style: str = "default"  # "default", "success", "warning", "danger"


@dataclass
class FlowSubgraph:
    """A labeled group of nodes in a flow diagram."""
    id: str
    label: str
    nodes: list[FlowNode] = field(default_factory=list)
    drill_down_view: str | None = None  # "ci", "deploy", "runtime", "auth"


@dataclass
class FlowEdge:
    """An edge in a flow diagram."""
    source: str
    target: str
    label: str | None = None
    style: str = "solid"    # "solid", "dotted", "thick"


@dataclass
class FlowDiagram:
    """A complete flow diagram — the single source of truth for all renderers."""
    title: str
    view: str               # "overview", "ci", "deploy", "runtime", "auth"
    direction: str = "LR"
    subgraphs: list[FlowSubgraph] = field(default_factory=list)
    loose_nodes: list[FlowNode] = field(default_factory=list)
    edges: list[FlowEdge] = field(default_factory=list)
    stats: dict = field(default_factory=dict)

    def all_nodes(self) -> list[FlowNode]:
        """Return all nodes across subgraphs and loose nodes."""
        result = list(self.loose_nodes)
        for sg in self.subgraphs:
            result.extend(sg.nodes)
        return result

    def to_dict(self) -> dict:
        """Serialize to a plain dict (for JSON renderer and API responses)."""
        return {
            "title": self.title,
            "view": self.view,
            "direction": self.direction,
            "subgraphs": [
                {
                    "id": sg.id,
                    "label": sg.label,
                    "drill_down_view": sg.drill_down_view,
                    "nodes": [{"id": n.id, "label": n.label, "kind": n.kind, "properties": n.properties, "style": n.style} for n in sg.nodes],
                }
                for sg in self.subgraphs
            ],
            "loose_nodes": [{"id": n.id, "label": n.label, "kind": n.kind, "properties": n.properties, "style": n.style} for n in self.loose_nodes],
            "edges": [{"source": e.source, "target": e.target, "label": e.label, "style": e.style} for e in self.edges],
            "stats": self.stats,
        }
