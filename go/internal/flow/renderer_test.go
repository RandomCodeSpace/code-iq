package flow_test

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/flow"
)

// sampleDiagram is a fixed in-memory diagram exercised across renderer tests.
func sampleDiagram() *flow.Diagram {
	d := flow.NewDiagram("Sample", "overview")
	d.LooseNodes = []flow.Node{flow.NewNode("alpha", "Alpha (A)", "code")}
	d.Subgraphs = []flow.Subgraph{
		flow.NewSubgraph("group1", "G1", []flow.Node{
			flow.NewNode("n1", "N1", "endpoint"),
			flow.NewNode("n2", "N2", "entity"),
		}),
	}
	d.Edges = []flow.Edge{
		flow.NewLabelEdge("n1", "n2", "queries"),
		flow.NewStyledEdge("n2", "alpha", "", "dotted"),
		flow.NewEdge("ghost", "alpha"), // dangling — must be filtered.
	}
	d.Stats = map[string]any{"total_nodes": 3}
	return d
}

// TestRenderJSONHasExpectedKeys asserts JSON output contains the canonical
// top-level keys (title, view, direction, subgraphs, loose_nodes, nodes,
// edges, stats) and the dangling edge is filtered out.
func TestRenderJSONHasExpectedKeys(t *testing.T) {
	d := sampleDiagram()
	out, err := flow.RenderJSON(d)
	if err != nil {
		t.Fatalf("RenderJSON: %v", err)
	}
	var got map[string]any
	if err := json.Unmarshal([]byte(out), &got); err != nil {
		t.Fatalf("invalid JSON: %v\n%s", err, out)
	}
	for _, k := range []string{"title", "view", "direction", "subgraphs", "loose_nodes", "nodes", "edges", "stats"} {
		if _, ok := got[k]; !ok {
			t.Errorf("JSON output missing %q\n%s", k, out)
		}
	}
	edges, _ := got["edges"].([]any)
	if len(edges) != 2 {
		t.Errorf("expected 2 valid edges (dangling filtered), got %d: %v", len(edges), edges)
	}
}

// TestRenderMermaidStartsWithGraph asserts the Mermaid output begins with
// `graph LR` for an LR-direction diagram.
func TestRenderMermaidStartsWithGraph(t *testing.T) {
	d := sampleDiagram()
	out := flow.RenderMermaid(d)
	if !strings.HasPrefix(out, "graph LR\n") {
		t.Fatalf("Mermaid output must start with `graph LR`, got:\n%s", out)
	}
	if !strings.Contains(out, "subgraph group1[\"G1\"]") {
		t.Errorf("Mermaid output missing subgraph block:\n%s", out)
	}
	// Dangling edge must be absent.
	if strings.Contains(out, "ghost") {
		t.Errorf("Mermaid output contains dangling edge:\n%s", out)
	}
}

// TestRenderMermaidEscapesSpecialChars asserts characters that Mermaid
// treats as syntax tokens are HTML-escaped in node labels.
func TestRenderMermaidEscapesSpecialChars(t *testing.T) {
	d := flow.NewDiagram("t", "overview")
	d.LooseNodes = []flow.Node{flow.NewNode("x", `name [v1]`, "code")}
	out := flow.RenderMermaid(d)
	if !strings.Contains(out, "&#91;") {
		t.Errorf("Mermaid output did not escape '[':\n%s", out)
	}
	if !strings.Contains(out, "&#93;") {
		t.Errorf("Mermaid output did not escape ']':\n%s", out)
	}
}

// TestRenderDOTStartsWithDigraph asserts the DOT output begins with `digraph G {`.
func TestRenderDOTStartsWithDigraph(t *testing.T) {
	d := sampleDiagram()
	out := flow.RenderDOT(d)
	if !strings.HasPrefix(out, "digraph G {") {
		t.Fatalf("DOT must start with `digraph G {`, got:\n%s", out)
	}
	if !strings.Contains(out, "subgraph cluster_group1 {") {
		t.Errorf("DOT missing cluster block:\n%s", out)
	}
	if !strings.HasSuffix(strings.TrimSpace(out), "}") {
		t.Errorf("DOT must end with `}`, got:\n%s", out)
	}
}

// TestRenderYAMLContainsKeys asserts the YAML output contains the same
// canonical top-level keys as the JSON output.
func TestRenderYAMLContainsKeys(t *testing.T) {
	d := sampleDiagram()
	out, err := flow.RenderYAML(d)
	if err != nil {
		t.Fatalf("RenderYAML: %v", err)
	}
	for _, k := range []string{"title:", "view:", "direction:", "subgraphs:", "loose_nodes:", "nodes:", "edges:", "stats:"} {
		if !strings.Contains(out, k) {
			t.Errorf("YAML output missing key %q\n%s", k, out)
		}
	}
}

// TestRenderDispatch asserts the Render dispatch picks the right backend
// for each format token. YAML output ordering follows go-yaml's
// alphabetical-map default; we just sanity-check the first non-empty
// rendering for each format.
func TestRenderDispatch(t *testing.T) {
	d := sampleDiagram()
	for _, tc := range []struct {
		format     string
		contains   string
	}{
		{"json", `"title"`},
		{"mermaid", "graph "},
		{"dot", "digraph"},
		{"yaml", "title:"},
	} {
		t.Run(tc.format, func(t *testing.T) {
			out, err := flow.Render(d, tc.format)
			if err != nil {
				t.Fatalf("Render(%q): %v", tc.format, err)
			}
			if !strings.Contains(out, tc.contains) {
				t.Errorf("Render(%q) does not contain %q:\n%s", tc.format, tc.contains, out)
			}
		})
	}
	if _, err := flow.Render(d, "bogus"); err == nil {
		t.Error("Render(bogus) must return an error")
	}
}

// TestSanitizeIDStripsPunctuation asserts non-word characters are replaced
// with underscores in node IDs.
func TestSanitizeIDStripsPunctuation(t *testing.T) {
	d := flow.NewDiagram("t", "overview")
	d.LooseNodes = []flow.Node{flow.NewNode("svc:foo-bar.baz", "Label", "code")}
	out := flow.RenderMermaid(d)
	if !strings.Contains(out, "svc_foo_bar_baz") {
		t.Errorf("Mermaid did not sanitize node id:\n%s", out)
	}
}
