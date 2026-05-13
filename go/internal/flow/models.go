package flow

// Models for flow diagrams — the single source of truth for every renderer.
// Mirrors src/main/java/.../flow/FlowModels.java.

// Node is one node in a flow diagram. The diagram is a collapsed /
// summarized view of the underlying graph, so a single flow Node frequently
// represents a *category* of graph nodes (e.g. "Endpoints x42").
type Node struct {
	ID         string         `json:"id"`
	Label      string         `json:"label"`
	Kind       string         `json:"kind"`
	Style      string         `json:"style"`
	Properties map[string]any `json:"properties"`
}

// NewNode constructs a Node with default style ("default") and empty
// properties. Use NewNodeWithProps / NewNodeWithStyle for richer cases.
func NewNode(id, label, kind string) Node {
	return Node{ID: id, Label: label, Kind: kind, Style: "default", Properties: map[string]any{}}
}

// NewNodeWithProps constructs a Node with the supplied properties map.
func NewNodeWithProps(id, label, kind string, props map[string]any) Node {
	if props == nil {
		props = map[string]any{}
	}
	return Node{ID: id, Label: label, Kind: kind, Style: "default", Properties: props}
}

// NewNodeWithStyle constructs a Node with an explicit style class
// (default | success | warning | danger). The style maps to Mermaid
// classDef and DOT color attributes.
func NewNodeWithStyle(id, label, kind, style string, props map[string]any) Node {
	if props == nil {
		props = map[string]any{}
	}
	return Node{ID: id, Label: label, Kind: kind, Style: style, Properties: props}
}

// Edge is one edge in a flow diagram. Edges are filtered against the set
// of valid node IDs during rendering — dangling edges are dropped.
type Edge struct {
	Source string `json:"source"`
	Target string `json:"target"`
	Label  string `json:"label,omitempty"`
	Style  string `json:"style"`
}

// NewEdge constructs a solid, unlabelled edge.
func NewEdge(source, target string) Edge {
	return Edge{Source: source, Target: target, Style: "solid"}
}

// NewLabelEdge constructs a solid edge with a label.
func NewLabelEdge(source, target, label string) Edge {
	return Edge{Source: source, Target: target, Label: label, Style: "solid"}
}

// NewStyledEdge constructs an edge with an explicit style
// (solid | dotted | thick).
func NewStyledEdge(source, target, label, style string) Edge {
	return Edge{Source: source, Target: target, Label: label, Style: style}
}

// Subgraph is a labelled group of nodes. Subgraphs may declare a
// drill-down view that the UI follows when the user expands the group.
type Subgraph struct {
	ID            string `json:"id"`
	Label         string `json:"label"`
	Nodes         []Node `json:"nodes"`
	DrillDownView string `json:"drill_down_view,omitempty"`
	ParentView    string `json:"parent_view,omitempty"`
}

// NewSubgraph constructs a subgraph with no drill-down hint.
func NewSubgraph(id, label string, nodes []Node) Subgraph {
	if nodes == nil {
		nodes = []Node{}
	}
	return Subgraph{ID: id, Label: label, Nodes: nodes}
}

// NewSubgraphWithDrillDown constructs a subgraph with a drill-down view.
func NewSubgraphWithDrillDown(id, label string, nodes []Node, drillDownView string) Subgraph {
	if nodes == nil {
		nodes = []Node{}
	}
	return Subgraph{ID: id, Label: label, Nodes: nodes, DrillDownView: drillDownView}
}

// Diagram is the complete flow diagram. Renderers consume this structure
// directly; no other shape is exposed.
type Diagram struct {
	Title      string         `json:"title"`
	View       string         `json:"view"`
	Direction  string         `json:"direction"`
	Subgraphs  []Subgraph     `json:"subgraphs"`
	LooseNodes []Node         `json:"loose_nodes"`
	Edges      []Edge         `json:"edges"`
	Stats      map[string]any `json:"stats"`
}

// NewDiagram constructs an empty diagram for the supplied view with the
// default left-to-right ("LR") direction and pre-allocated slices/maps.
func NewDiagram(title, view string) *Diagram {
	return &Diagram{
		Title:      title,
		View:       view,
		Direction:  "LR",
		Subgraphs:  []Subgraph{},
		LooseNodes: []Node{},
		Edges:      []Edge{},
		Stats:      map[string]any{},
	}
}

// AllNodes returns every node across both loose nodes and subgraph nodes.
// Order matches the Java side: loose nodes first, then each subgraph's
// nodes in subgraph order.
func (d *Diagram) AllNodes() []Node {
	if d == nil {
		return nil
	}
	out := make([]Node, 0, len(d.LooseNodes))
	out = append(out, d.LooseNodes...)
	for _, sg := range d.Subgraphs {
		out = append(out, sg.Nodes...)
	}
	return out
}

// ValidEdges returns the edges whose source AND target IDs exist on a node
// in the diagram. Dangling references are silently dropped — matches the
// Java side's FlowDiagram.toMap behaviour.
func (d *Diagram) ValidEdges() []Edge {
	if d == nil {
		return nil
	}
	ids := make(map[string]struct{}, len(d.LooseNodes))
	for _, n := range d.AllNodes() {
		ids[n.ID] = struct{}{}
	}
	out := make([]Edge, 0, len(d.Edges))
	for _, e := range d.Edges {
		_, srcOK := ids[e.Source]
		_, tgtOK := ids[e.Target]
		if srcOK && tgtOK {
			out = append(out, e)
		}
	}
	return out
}
