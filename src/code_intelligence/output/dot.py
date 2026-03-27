"""Graphviz DOT renderer for the code intelligence graph."""

from __future__ import annotations

import re
from typing import Literal

from code_intelligence.graph.store import GraphStore
from code_intelligence.models.graph import EdgeKind, GraphEdge, GraphNode, NodeKind


def _sanitize_id(raw: str) -> str:
    """Replace characters invalid in DOT identifiers."""
    return re.sub(r"[^a-zA-Z0-9_]", "_", raw)


def _quote(text: str) -> str:
    """Escape a string for use inside DOT double-quotes."""
    return text.replace("\\", "\\\\").replace('"', '\\"')


# -- Node shape and colour mapping -----------------------------------------

_NODE_STYLES: dict[NodeKind, dict[str, str]] = {
    NodeKind.MODULE:             {"shape": "box3d",       "fillcolor": "#4A90D9", "fontcolor": "white"},
    NodeKind.PACKAGE:            {"shape": "box3d",       "fillcolor": "#4A90D9", "fontcolor": "white"},
    NodeKind.CLASS:              {"shape": "box",         "fillcolor": "#A8D8EA", "fontcolor": "black"},
    NodeKind.METHOD:             {"shape": "box",         "fillcolor": "#D4E6F1", "fontcolor": "black"},
    NodeKind.ENDPOINT:           {"shape": "hexagon",     "fillcolor": "#F9E79F", "fontcolor": "black"},
    NodeKind.ENTITY:             {"shape": "cylinder",    "fillcolor": "#ABEBC6", "fontcolor": "black"},
    NodeKind.REPOSITORY:         {"shape": "cylinder",    "fillcolor": "#ABEBC6", "fontcolor": "black"},
    NodeKind.QUERY:              {"shape": "box",         "fillcolor": "#D5F5E3", "fontcolor": "black"},
    NodeKind.MIGRATION:          {"shape": "box",         "fillcolor": "#D5F5E3", "fontcolor": "black"},
    NodeKind.TOPIC:              {"shape": "parallelogram", "fillcolor": "#F5B7B1", "fontcolor": "black"},
    NodeKind.QUEUE:              {"shape": "parallelogram", "fillcolor": "#F5B7B1", "fontcolor": "black"},
    NodeKind.EVENT:              {"shape": "parallelogram", "fillcolor": "#F5B7B1", "fontcolor": "black"},
    NodeKind.RMI_INTERFACE:      {"shape": "component",  "fillcolor": "#D7BDE2", "fontcolor": "black"},
    NodeKind.CONFIG_FILE:        {"shape": "note",        "fillcolor": "#FDEBD0", "fontcolor": "black"},
    NodeKind.CONFIG_KEY:         {"shape": "note",        "fillcolor": "#FDEBD0", "fontcolor": "black"},
    NodeKind.WEBSOCKET_ENDPOINT: {"shape": "hexagon",     "fillcolor": "#F9E79F", "fontcolor": "black"},
}

# -- Edge styles -----------------------------------------------------------

_EDGE_STYLES: dict[EdgeKind, dict[str, str]] = {
    EdgeKind.CALLS:       {"style": "solid",  "arrowhead": "normal"},
    EdgeKind.DEPENDS_ON:  {"style": "solid",  "arrowhead": "normal"},
    EdgeKind.IMPORTS:     {"style": "solid",  "arrowhead": "normal"},
    EdgeKind.INJECTS:     {"style": "solid",  "arrowhead": "normal"},
    EdgeKind.QUERIES:     {"style": "solid",  "arrowhead": "normal"},
    EdgeKind.MAPS_TO:     {"style": "solid",  "arrowhead": "normal"},
    EdgeKind.READS_CONFIG: {"style": "solid", "arrowhead": "normal"},
    EdgeKind.MIGRATES:    {"style": "solid",  "arrowhead": "normal"},
    EdgeKind.CONTAINS:    {"style": "solid",  "arrowhead": "normal"},
    EdgeKind.EXPOSES:     {"style": "solid",  "arrowhead": "normal"},
    EdgeKind.PRODUCES:    {"style": "dashed", "arrowhead": "normal"},
    EdgeKind.CONSUMES:    {"style": "dashed", "arrowhead": "normal"},
    EdgeKind.PUBLISHES:   {"style": "dashed", "arrowhead": "normal"},
    EdgeKind.LISTENS:     {"style": "dashed", "arrowhead": "normal"},
    EdgeKind.INVOKES_RMI: {"style": "dashed", "arrowhead": "normal"},
    EdgeKind.EXPORTS_RMI: {"style": "dashed", "arrowhead": "normal"},
    EdgeKind.EXTENDS:     {"style": "solid",  "arrowhead": "empty"},
    EdgeKind.IMPLEMENTS:  {"style": "solid",  "arrowhead": "empty"},
}


ClusterBy = Literal["module", "domain", "node-type", None]


class DotRenderer:
    """Render a :class:`GraphStore` as a Graphviz DOT graph."""

    def __init__(
        self,
        rankdir: str = "LR",
        cluster_by: ClusterBy = None,
        fontname: str = "Helvetica",
        fontsize: str = "11",
    ) -> None:
        self._rankdir = rankdir
        self._cluster_by = cluster_by
        self._fontname = fontname
        self._fontsize = fontsize

    # -- public API -------------------------------------------------

    def render(self, store: GraphStore, cluster_by: ClusterBy | None = None) -> str:
        """Return a DOT-language string."""
        lines: list[str] = [
            "digraph CodeIntelligence {",
            f'    rankdir={self._rankdir};',
            f'    fontname="{self._fontname}";',
            f'    fontsize={self._fontsize};',
            f'    node [fontname="{self._fontname}", fontsize={self._fontsize}, style=filled];',
            f'    edge [fontname="{self._fontname}", fontsize={self._fontsize}];',
            "",
        ]

        nodes = store.all_nodes()
        edges = store.all_edges()

        if self._cluster_by:
            lines.extend(self._render_clustered(nodes))
        else:
            for node in nodes:
                lines.append(self._node_def(node))
            lines.append("")

        for edge in edges:
            lines.append(self._edge_def(edge))

        lines.append("}")
        return "\n".join(lines) + "\n"

    # -- internal ---------------------------------------------------

    def _node_def(self, node: GraphNode) -> str:
        sid = _sanitize_id(node.id)
        style = _NODE_STYLES.get(node.kind, {"shape": "box", "fillcolor": "#FFFFFF", "fontcolor": "black"})
        label = _quote(node.label)
        attrs = (
            f'label="{label}", '
            f'shape={style["shape"]}, '
            f'fillcolor="{style["fillcolor"]}", '
            f'fontcolor="{style["fontcolor"]}"'
        )
        return f"    {sid} [{attrs}];"

    def _edge_def(self, edge: GraphEdge) -> str:
        src = _sanitize_id(edge.source)
        tgt = _sanitize_id(edge.target)
        style = _EDGE_STYLES.get(
            edge.kind,
            {"style": "solid", "arrowhead": "normal"},
        )
        label = _quote(edge.label or edge.kind.value)
        attrs = (
            f'label="{label}", '
            f'style={style["style"]}, '
            f'arrowhead={style["arrowhead"]}'
        )
        return f"    {src} -> {tgt} [{attrs}];"

    def _cluster_key(self, node: GraphNode) -> str:
        if self._cluster_by == "module":
            return node.module or "unknown"
        if self._cluster_by == "domain":
            return node.properties.get("domain", node.module or "unknown")  # type: ignore[return-value]
        if self._cluster_by == "node-type":
            return node.kind.value
        return "default"

    def _render_clustered(self, nodes: list[GraphNode]) -> list[str]:
        clusters: dict[str, list[GraphNode]] = {}
        for node in nodes:
            key = self._cluster_key(node)
            clusters.setdefault(key, []).append(node)

        lines: list[str] = []
        for idx, (cluster_name, cluster_nodes) in enumerate(sorted(clusters.items())):
            sub_id = _sanitize_id(f"cluster_{cluster_name}")
            lines.append(f"    subgraph {sub_id} {{")
            lines.append(f'        label="{_quote(cluster_name)}";')
            lines.append('        style=filled;')
            lines.append('        color="#E8E8E8";')
            for node in cluster_nodes:
                lines.append(f"    {self._node_def(node)}")
            lines.append("    }")
            lines.append("")

        return lines
