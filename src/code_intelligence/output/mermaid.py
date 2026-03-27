"""Mermaid diagram renderer for the code intelligence graph."""

from __future__ import annotations

import re
from typing import Literal

from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind


def _sanitize_id(raw: str) -> str:
    """Replace characters that are invalid in Mermaid node IDs."""
    return re.sub(r"[^a-zA-Z0-9_]", "_", raw)


def _escape_label(text: str) -> str:
    """Escape Mermaid special characters in labels."""
    for ch in ('"', '|', '[', ']', '{', '}', '(', ')', '<', '>', '#'):
        text = text.replace(ch, f"&#{ord(ch)};")
    return text


# -- Node shape templates --------------------------------------------------
# Mermaid syntax: id[label], id([label]), id{{label}}, id[(label)], etc.

_NODE_SHAPES: dict[NodeKind, tuple[str, str]] = {
    NodeKind.MODULE:             ("[",  "]"),       # rectangle
    NodeKind.PACKAGE:            ("[",  "]"),
    NodeKind.CLASS:              ("[",  "]"),
    NodeKind.METHOD:             ("([", "])"),       # stadium / pill
    NodeKind.ENDPOINT:           ("{{", "}}"),       # hexagon
    NodeKind.ENTITY:             ("[(", ")]"),       # cylinder
    NodeKind.REPOSITORY:         ("[(", ")]"),
    NodeKind.QUERY:              ("([", "])"),
    NodeKind.MIGRATION:          ("([", "])"),
    NodeKind.TOPIC:              ("[/", "/]"),       # parallelogram
    NodeKind.QUEUE:              ("[/", "/]"),
    NodeKind.EVENT:              ("[/", "/]"),
    NodeKind.RMI_INTERFACE:      ("[[", "]]"),      # subroutine
    NodeKind.CONFIG_FILE:        ("([", "])"),
    NodeKind.CONFIG_KEY:         ("([", "])"),
    NodeKind.WEBSOCKET_ENDPOINT: ("{{", "}}"),
}

# -- Edge arrow styles ------------------------------------------------------

_EDGE_STYLES: dict[EdgeKind, str] = {
    # Solid arrow  -->
    EdgeKind.CALLS:       "-->",
    EdgeKind.DEPENDS_ON:  "-->",
    EdgeKind.IMPORTS:     "-->",
    EdgeKind.INJECTS:     "-->",
    EdgeKind.QUERIES:     "-->",
    EdgeKind.MAPS_TO:     "-->",
    EdgeKind.READS_CONFIG: "-->",
    EdgeKind.MIGRATES:    "-->",
    EdgeKind.CONTAINS:    "-->",
    EdgeKind.EXPOSES:     "-->",
    # Dotted arrow  -.->
    EdgeKind.PRODUCES:    "-.->",
    EdgeKind.CONSUMES:    "-.->",
    EdgeKind.PUBLISHES:   "-.->",
    EdgeKind.LISTENS:     "-.->",
    EdgeKind.INVOKES_RMI: "-.->",
    EdgeKind.EXPORTS_RMI: "-.->",
    # Open arrowhead  --o
    EdgeKind.EXTENDS:     "--o",
    EdgeKind.IMPLEMENTS:  "--o",
}


ClusterBy = Literal["module", "domain", "node-type", None]


class MermaidRenderer:
    """Render a :class:`GraphStore` as a Mermaid flowchart."""

    def __init__(
        self,
        direction: str = "LR",
        cluster_by: ClusterBy = None,
    ) -> None:
        self._direction = direction
        self._cluster_by = cluster_by

    # -- public API -------------------------------------------------

    def render(self, store: GraphStore, cluster_by: ClusterBy | None = None) -> str:
        """Return a Mermaid flowchart string."""
        effective_cluster = cluster_by or self._cluster_by
        lines: list[str] = [f"graph {self._direction}"]

        nodes = store.all_nodes()
        edges = store.all_edges()

        if effective_cluster:
            old = self._cluster_by
            self._cluster_by = effective_cluster
            lines.extend(self._render_clustered(nodes, edges))
            self._cluster_by = old
        else:
            lines.extend(self._render_flat(nodes, edges))

        return "\n".join(lines) + "\n"

    # -- internal ---------------------------------------------------

    def _node_def(self, node: GraphNode) -> str:
        sid = _sanitize_id(node.id)
        left, right = _NODE_SHAPES.get(node.kind, ("[", "]"))
        label = _escape_label(node.label)
        return f"    {sid}{left}\"{label}\"{right}"

    def _edge_def(self, edge: GraphEdge) -> str:
        src = _sanitize_id(edge.source)
        tgt = _sanitize_id(edge.target)
        arrow = _EDGE_STYLES.get(edge.kind, "-->")
        label = _escape_label(edge.label or edge.kind.value)
        return f"    {src} {arrow}|{label}| {tgt}"

    def _cluster_key(self, node: GraphNode) -> str:
        if self._cluster_by == "module":
            return node.module or "unknown"
        if self._cluster_by == "domain":
            return node.properties.get("domain", node.module or "unknown")  # type: ignore[return-value]
        if self._cluster_by == "node-type":
            return node.kind.value
        return "default"

    def _render_flat(
        self, nodes: list[GraphNode], edges: list[GraphEdge]
    ) -> list[str]:
        lines: list[str] = []
        for node in nodes:
            lines.append(self._node_def(node))
        for edge in edges:
            lines.append(self._edge_def(edge))
        return lines

    def _render_clustered(
        self, nodes: list[GraphNode], edges: list[GraphEdge]
    ) -> list[str]:
        clusters: dict[str, list[GraphNode]] = {}
        for node in nodes:
            key = self._cluster_key(node)
            clusters.setdefault(key, []).append(node)

        lines: list[str] = []
        for idx, (cluster_name, cluster_nodes) in enumerate(sorted(clusters.items())):
            sub_id = _sanitize_id(f"cluster_{cluster_name}")
            lines.append(f"    subgraph {sub_id}[\"{cluster_name}\"]")
            for node in cluster_nodes:
                lines.append(f"    {self._node_def(node)}")
            lines.append("    end")

        for edge in edges:
            lines.append(self._edge_def(edge))

        return lines
