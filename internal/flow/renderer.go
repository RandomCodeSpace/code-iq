package flow

import (
	"encoding/json"
	"fmt"
	"regexp"
	"sort"
	"strings"

	"gopkg.in/yaml.v3"
)

// Renderer emits a Diagram in one of four output formats: JSON (default),
// Mermaid (flowchart), DOT (Graphviz), or YAML. Mirrors
// src/main/java/.../flow/FlowRenderer.java with two extras (DOT, YAML) the
// Java side does not yet ship — the Go port adds them per phase 3 plan
// task 6.3.

// shapeBrackets maps a node `kind` to its Mermaid bracket pair. Mirrors
// the SHAPES table in FlowRenderer.java.
var shapeBrackets = map[string][2]string{
	"trigger":    {"([", "])"},
	"pipeline":   {"[", "]"},
	"job":        {"[", "]"},
	"endpoint":   {"{{", "}}"},
	"entity":     {"[(", ")]"},
	"database":   {"[(", ")]"},
	"guard":      {">", "]"},
	"middleware": {">", "]"},
	"component":  {"([", "])"},
	"messaging":  {"[/", "\\]"},
	"k8s":        {"[", "]"},
	"docker":     {"[", "]"},
	"terraform":  {"[", "]"},
	"infra":      {"[", "]"},
	"code":       {"[", "]"},
	"service":    {"[", "]"},
}

// edgeArrow maps an edge style to its Mermaid arrow token.
var edgeArrow = map[string]string{
	"solid":  "-->",
	"dotted": "-.->",
	"thick":  "==>",
}

// styleSuffix maps a node style to its Mermaid classDef suffix.
var styleSuffix = map[string]string{
	"success": ":::success",
	"warning": ":::warning",
	"danger":  ":::danger",
	"default": "",
}

// nonWord matches every character outside [A-Za-z0-9_] — used to sanitize
// Mermaid / DOT node IDs.
var nonWord = regexp.MustCompile(`\W`)

// Render emits the Diagram in the requested format. Recognised formats:
// json (default), mermaid, dot, yaml. Returns an error for unknown formats.
func Render(d *Diagram, format string) (string, error) {
	switch strings.ToLower(strings.TrimSpace(format)) {
	case "", "json":
		return RenderJSON(d)
	case "mermaid":
		return RenderMermaid(d), nil
	case "dot":
		return RenderDOT(d), nil
	case "yaml", "yml":
		return RenderYAML(d)
	default:
		return "", fmt.Errorf("flow: unknown format %q (valid: json, mermaid, dot, yaml)", format)
	}
}

// RenderJSON emits the diagram as indented JSON. Field names match the Java
// FlowRenderer.renderJson output exactly so parity diffs match 1:1.
func RenderJSON(d *Diagram) (string, error) {
	body, err := json.MarshalIndent(d.toJSONMap(), "", "  ")
	if err != nil {
		return "", fmt.Errorf("flow: render json: %w", err)
	}
	return string(body), nil
}

// RenderYAML emits the diagram as YAML. The mapping uses the same key
// names as the JSON output.
func RenderYAML(d *Diagram) (string, error) {
	body, err := yaml.Marshal(d.toJSONMap())
	if err != nil {
		return "", fmt.Errorf("flow: render yaml: %w", err)
	}
	return string(body), nil
}

// RenderMermaid emits the diagram as a Mermaid `graph` flowchart string.
// The output is deterministic — nodes within each subgraph and edges are
// sorted by ID before emission.
func RenderMermaid(d *Diagram) string {
	var sb strings.Builder
	dir := d.Direction
	if dir == "" {
		dir = "LR"
	}
	sb.WriteString("graph ")
	sb.WriteString(dir)
	sb.WriteByte('\n')
	sb.WriteString("    classDef success fill:#d4edda,stroke:#28a745,color:#155724\n")
	sb.WriteString("    classDef warning fill:#fff3cd,stroke:#ffc107,color:#856404\n")
	sb.WriteString("    classDef danger fill:#f8d7da,stroke:#dc3545,color:#721c24\n")
	sb.WriteByte('\n')

	for _, sg := range d.Subgraphs {
		fmt.Fprintf(&sb, "    subgraph %s[\"%s\"]\n", sanitizeID(sg.ID), escapeLabel(sg.Label))
		sorted := append([]Node(nil), sg.Nodes...)
		sort.Slice(sorted, func(i, j int) bool { return sorted[i].ID < sorted[j].ID })
		for _, n := range sorted {
			appendMermaidNode(&sb, n, "        ")
		}
		sb.WriteString("    end\n\n")
	}

	loose := append([]Node(nil), d.LooseNodes...)
	sort.Slice(loose, func(i, j int) bool { return loose[i].ID < loose[j].ID })
	for _, n := range loose {
		appendMermaidNode(&sb, n, "    ")
	}

	sb.WriteByte('\n')
	validEdges := d.ValidEdges()
	sort.Slice(validEdges, func(i, j int) bool {
		if validEdges[i].Source != validEdges[j].Source {
			return validEdges[i].Source < validEdges[j].Source
		}
		return validEdges[i].Target < validEdges[j].Target
	})
	for _, e := range validEdges {
		src := sanitizeID(e.Source)
		tgt := sanitizeID(e.Target)
		arrow := edgeArrow[e.Style]
		if arrow == "" {
			arrow = "-->"
		}
		if e.Label != "" {
			fmt.Fprintf(&sb, "    %s %s|%s| %s\n", src, arrow, escapeLabel(e.Label), tgt)
		} else {
			fmt.Fprintf(&sb, "    %s %s %s\n", src, arrow, tgt)
		}
	}
	return sb.String()
}

// RenderDOT emits the diagram as a Graphviz DOT digraph string. Subgraphs
// are emitted as `cluster_*` for visual grouping in Graphviz output.
func RenderDOT(d *Diagram) string {
	var sb strings.Builder
	dir := d.Direction
	if dir == "" {
		dir = "LR"
	}
	sb.WriteString("digraph G {\n")
	fmt.Fprintf(&sb, "    rankdir=%s;\n", dir)
	sb.WriteString("    node [shape=box, fontname=\"Helvetica\"];\n\n")

	for _, sg := range d.Subgraphs {
		fmt.Fprintf(&sb, "    subgraph cluster_%s {\n", sanitizeID(sg.ID))
		fmt.Fprintf(&sb, "        label=\"%s\";\n", escapeDOTLabel(sg.Label))
		sorted := append([]Node(nil), sg.Nodes...)
		sort.Slice(sorted, func(i, j int) bool { return sorted[i].ID < sorted[j].ID })
		for _, n := range sorted {
			fmt.Fprintf(&sb, "        %s [label=\"%s\"%s];\n",
				sanitizeID(n.ID), escapeDOTLabel(n.Label), dotStyleAttr(n.Style))
		}
		sb.WriteString("    }\n")
	}

	loose := append([]Node(nil), d.LooseNodes...)
	sort.Slice(loose, func(i, j int) bool { return loose[i].ID < loose[j].ID })
	for _, n := range loose {
		fmt.Fprintf(&sb, "    %s [label=\"%s\"%s];\n",
			sanitizeID(n.ID), escapeDOTLabel(n.Label), dotStyleAttr(n.Style))
	}

	validEdges := d.ValidEdges()
	sort.Slice(validEdges, func(i, j int) bool {
		if validEdges[i].Source != validEdges[j].Source {
			return validEdges[i].Source < validEdges[j].Source
		}
		return validEdges[i].Target < validEdges[j].Target
	})
	if len(validEdges) > 0 {
		sb.WriteByte('\n')
	}
	for _, e := range validEdges {
		extras := ""
		if e.Label != "" {
			extras = fmt.Sprintf(" [label=\"%s\"%s]", escapeDOTLabel(e.Label), dotEdgeStyle(e.Style))
		} else if e.Style != "" && e.Style != "solid" {
			extras = fmt.Sprintf(" [%s]", strings.TrimPrefix(dotEdgeStyle(e.Style), ", "))
		}
		fmt.Fprintf(&sb, "    %s -> %s%s;\n", sanitizeID(e.Source), sanitizeID(e.Target), extras)
	}

	sb.WriteString("}\n")
	return sb.String()
}

// --- helpers ---

func appendMermaidNode(sb *strings.Builder, n Node, indent string) {
	id := sanitizeID(n.ID)
	label := escapeLabel(n.Label)
	brackets, ok := shapeBrackets[n.Kind]
	if !ok {
		brackets = [2]string{"[", "]"}
	}
	suffix := styleSuffix[n.Style]
	fmt.Fprintf(sb, "%s%s%s\"%s\"%s%s\n", indent, id, brackets[0], label, brackets[1], suffix)
}

// sanitizeID replaces every non-word character with '_'. Mermaid and DOT
// both reject IDs containing punctuation; this matches the Java
// FlowRenderer.sanitizeId regex behaviour.
func sanitizeID(raw string) string {
	return nonWord.ReplaceAllString(raw, "_")
}

// escapeLabel HTML-escapes characters that Mermaid would otherwise
// interpret as syntax tokens. Order matters — process '#' first so the
// '&#…' sequences emitted by later replacements are NOT re-escaped.
//
// Mirrors FlowRenderer.escapeLabel exactly.
func escapeLabel(text string) string {
	if text == "" {
		return ""
	}
	text = strings.ReplaceAll(text, "#", "&#35;")
	for _, ch := range []rune{'"', '|', '[', ']', '{', '}', '(', ')', '<', '>'} {
		text = strings.ReplaceAll(text, string(ch), fmt.Sprintf("&#%d;", ch))
	}
	return text
}

// escapeDOTLabel handles the smaller escape surface DOT requires — only
// double-quotes and backslashes need escaping.
func escapeDOTLabel(text string) string {
	text = strings.ReplaceAll(text, `\`, `\\`)
	text = strings.ReplaceAll(text, `"`, `\"`)
	return text
}

// dotStyleAttr maps a node style to a DOT attribute fragment.
func dotStyleAttr(style string) string {
	switch style {
	case "success":
		return `, style=filled, fillcolor="#d4edda", color="#28a745"`
	case "warning":
		return `, style=filled, fillcolor="#fff3cd", color="#ffc107"`
	case "danger":
		return `, style=filled, fillcolor="#f8d7da", color="#dc3545"`
	}
	return ""
}

// dotEdgeStyle maps an edge style to a DOT attribute fragment with a
// leading comma so it can splice into [label="...", style=...].
func dotEdgeStyle(style string) string {
	switch style {
	case "dotted":
		return `, style=dotted`
	case "thick":
		return `, penwidth=2`
	}
	return ""
}

// toJSONMap projects the diagram into a Java-parity map structure. The
// embedded `nodes` list flattens loose + subgraph nodes, mirroring the
// Java FlowDiagram.toMap behaviour.
func (d *Diagram) toJSONMap() map[string]any {
	subgraphs := make([]map[string]any, 0, len(d.Subgraphs))
	for _, sg := range d.Subgraphs {
		subgraphs = append(subgraphs, sg.toJSONMap())
	}
	loose := nodesToMaps(d.LooseNodes)
	all := nodesToMaps(d.AllNodes())
	validEdges := d.ValidEdges()
	edges := make([]map[string]any, 0, len(validEdges))
	for _, e := range validEdges {
		edges = append(edges, e.toJSONMap())
	}
	return map[string]any{
		"title":       d.Title,
		"view":        d.View,
		"direction":   d.Direction,
		"subgraphs":   subgraphs,
		"loose_nodes": loose,
		"nodes":       all,
		"edges":       edges,
		"stats":       d.Stats,
	}
}

func (n Node) toJSONMap() map[string]any {
	return map[string]any{
		"id":         n.ID,
		"label":      n.Label,
		"kind":       n.Kind,
		"style":      n.Style,
		"properties": n.Properties,
	}
}

func (e Edge) toJSONMap() map[string]any {
	return map[string]any{
		"source": e.Source,
		"target": e.Target,
		"label":  e.Label,
		"style":  e.Style,
	}
}

func (sg Subgraph) toJSONMap() map[string]any {
	return map[string]any{
		"id":              sg.ID,
		"label":           sg.Label,
		"drill_down_view": sg.DrillDownView,
		"parent_view":     sg.ParentView,
		"nodes":           nodesToMaps(sg.Nodes),
	}
}

func nodesToMaps(nodes []Node) []map[string]any {
	out := make([]map[string]any, 0, len(nodes))
	for _, n := range nodes {
		out = append(out, n.toJSONMap())
	}
	return out
}
