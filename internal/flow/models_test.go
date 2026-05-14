package flow_test

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/flow"
)

// TestDiagramAllNodes asserts AllNodes returns loose nodes first, then
// subgraph nodes in subgraph order — the contract every renderer relies on.
func TestDiagramAllNodes(t *testing.T) {
	d := flow.NewDiagram("title", "overview")
	d.LooseNodes = []flow.Node{flow.NewNode("loose1", "Loose 1", "code")}
	d.Subgraphs = []flow.Subgraph{
		flow.NewSubgraph("sg1", "SG 1", []flow.Node{flow.NewNode("n1", "N1", "code")}),
		flow.NewSubgraph("sg2", "SG 2", []flow.Node{flow.NewNode("n2", "N2", "code")}),
	}
	got := d.AllNodes()
	wantIDs := []string{"loose1", "n1", "n2"}
	if len(got) != len(wantIDs) {
		t.Fatalf("AllNodes len = %d, want %d", len(got), len(wantIDs))
	}
	for i, n := range got {
		if n.ID != wantIDs[i] {
			t.Errorf("AllNodes[%d].ID = %q, want %q", i, n.ID, wantIDs[i])
		}
	}
}

// TestDiagramValidEdgesFiltersDangling asserts edges whose source or target
// is missing from the node set are silently dropped.
func TestDiagramValidEdgesFiltersDangling(t *testing.T) {
	d := flow.NewDiagram("title", "overview")
	d.LooseNodes = []flow.Node{
		flow.NewNode("a", "A", "code"),
		flow.NewNode("b", "B", "code"),
	}
	d.Edges = []flow.Edge{
		flow.NewEdge("a", "b"),     // valid
		flow.NewEdge("a", "ghost"), // target dangling
		flow.NewEdge("ghost", "b"), // source dangling
	}
	got := d.ValidEdges()
	if len(got) != 1 {
		t.Fatalf("ValidEdges len = %d, want 1: %#v", len(got), got)
	}
	if got[0].Source != "a" || got[0].Target != "b" {
		t.Errorf("ValidEdges[0] = %+v, want a->b", got[0])
	}
}

// TestNodeFactoriesDefaultStyle asserts NewNode produces a Node with the
// "default" style and an empty properties map.
func TestNodeFactoriesDefaultStyle(t *testing.T) {
	n := flow.NewNode("id1", "Label", "code")
	if n.Style != "default" {
		t.Errorf("style = %q, want default", n.Style)
	}
	if n.Properties == nil || len(n.Properties) != 0 {
		t.Errorf("properties = %#v, want empty map", n.Properties)
	}
}
