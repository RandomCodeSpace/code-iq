"""Renderers for flow diagrams — Mermaid, JSON, and interactive HTML."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

from osscodeiq.flow.models import FlowDiagram, FlowEdge, FlowNode, FlowSubgraph


def _sanitize_id(raw: str) -> str:
    return re.sub(r"\W", "_", raw)


def _escape_label(text: str) -> str:
    for ch in ('"', '|', '[', ']', '{', '}', '(', ')', '<', '>', '#'):
        text = text.replace(ch, f"&#{ord(ch)};")
    return text


# Node shapes by kind
_SHAPES: dict[str, tuple[str, str]] = {
    "trigger":    ("([", "])"),     # stadium
    "pipeline":   ("[", "]"),       # rectangle
    "job":        ("[", "]"),
    "endpoint":   ("{{", "}}"),     # hexagon
    "entity":     ("[(", ")]"),     # cylinder
    "database":   ("[(", ")]"),
    "guard":      (">", "]"),       # flag
    "middleware":  (">", "]"),
    "component":  ("([", "])"),
    "messaging":  ("[/", "\\]"),    # parallelogram
    "k8s":        ("[", "]"),
    "docker":     ("[", "]"),
    "terraform":  ("[", "]"),
    "infra":      ("[", "]"),
    "code":       ("[", "]"),
    "service":    ("[", "]"),
}

_EDGE_STYLES = {
    "solid":  "-->",
    "dotted": "-.->",
    "thick":  "==>",
}

_STYLE_CLASSES = {
    "success": ":::success",
    "warning": ":::warning",
    "danger":  ":::danger",
    "default": "",
}


def render_mermaid(diagram: FlowDiagram) -> str:
    """Render a FlowDiagram as a Mermaid flowchart string."""
    lines = [f"graph {diagram.direction}"]

    # Style definitions
    lines.append("    classDef success fill:#d4edda,stroke:#28a745,color:#155724")
    lines.append("    classDef warning fill:#fff3cd,stroke:#ffc107,color:#856404")
    lines.append("    classDef danger fill:#f8d7da,stroke:#dc3545,color:#721c24")
    lines.append("")

    for sg in diagram.subgraphs:
        sg_id = _sanitize_id(sg.id)
        lines.append(f'    subgraph {sg_id}["{_escape_label(sg.label)}"]')
        for node in sorted(sg.nodes, key=lambda n: n.id):
            nid = _sanitize_id(node.id)
            label = _escape_label(node.label)
            open_br, close_br = _SHAPES.get(node.kind, ("[", "]"))
            style_class = _STYLE_CLASSES.get(node.style, "")
            lines.append(f"        {nid}{open_br}\"{label}\"{close_br}{style_class}")
        lines.append("    end")
        lines.append("")

    for node in sorted(diagram.loose_nodes, key=lambda n: n.id):
        nid = _sanitize_id(node.id)
        label = _escape_label(node.label)
        open_br, close_br = _SHAPES.get(node.kind, ("[", "]"))
        style_class = _STYLE_CLASSES.get(node.style, "")
        lines.append(f"    {nid}{open_br}\"{label}\"{close_br}{style_class}")

    lines.append("")
    for edge in sorted(diagram.edges, key=lambda e: (e.source, e.target)):
        src = _sanitize_id(edge.source)
        tgt = _sanitize_id(edge.target)
        arrow = _EDGE_STYLES.get(edge.style, "-->")
        if edge.label:
            lines.append(f"    {src} {arrow}|{_escape_label(edge.label)}| {tgt}")
        else:
            lines.append(f"    {src} {arrow} {tgt}")

    return "\n".join(lines)


def render_json(diagram: FlowDiagram) -> str:
    """Render a FlowDiagram as a JSON string."""
    return json.dumps(diagram.to_dict(), indent=2)


def render_html(views: dict[str, FlowDiagram], stats: dict[str, Any], project_name: str = "Project") -> str:
    """Render all views into a self-contained interactive HTML file."""
    views_data = {}
    for name, diagram in sorted(views.items()):
        views_data[name] = diagram.to_dict()

    template_path = Path(__file__).parent / "templates" / "interactive.html"
    template = template_path.read_text(encoding="utf-8")

    # Inline vendor JS for offline/firewall use
    vendor_dir = Path(__file__).parent / "vendor"
    for placeholder, filename in [
        ("{{VENDOR_DAGRE}}", "dagre.min.js"),
        ("{{VENDOR_CYTOSCAPE}}", "cytoscape.min.js"),
        ("{{VENDOR_CYTOSCAPE_DAGRE}}", "cytoscape-dagre.min.js"),
    ]:
        vendor_path = vendor_dir / filename
        template = template.replace(placeholder, vendor_path.read_text(encoding="utf-8"))

    html = template.replace("{{VIEWS_DATA}}", json.dumps(views_data, indent=2))
    html = html.replace("{{STATS}}", json.dumps(stats, indent=2))
    html = html.replace("{{PROJECT_NAME}}", json.dumps(project_name))

    return html
