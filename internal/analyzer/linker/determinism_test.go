package linker

import (
	"math/rand"
	"reflect"
	"testing"

	"github.com/randomcodespace/codeiq/internal/model"
)

// TestLinkerDeterminism_ShuffledInput — Plan §1.6.
// Same set of nodes/edges in two different orders must produce identical
// sorted output through Sorted().
func TestLinkerDeterminism_ShuffledInput(t *testing.T) {
	build := func(seed int64) Result {
		nodes := []*model.CodeNode{
			model.NewCodeNode("c", model.NodeClass, "c"),
			model.NewCodeNode("a", model.NodeClass, "a"),
			model.NewCodeNode("b", model.NodeClass, "b"),
		}
		edges := []*model.CodeEdge{
			model.NewCodeEdge("e3", model.EdgeCalls, "c", "a"),
			model.NewCodeEdge("e1", model.EdgeCalls, "a", "b"),
			model.NewCodeEdge("e2", model.EdgeCalls, "b", "c"),
		}
		r := rand.New(rand.NewSource(seed))
		r.Shuffle(len(nodes), func(i, j int) { nodes[i], nodes[j] = nodes[j], nodes[i] })
		r.Shuffle(len(edges), func(i, j int) { edges[i], edges[j] = edges[j], edges[i] })
		return Result{Nodes: nodes, Edges: edges}.Sorted()
	}

	r1 := build(1)
	r2 := build(2)
	if !sameNodeIDs(r1.Nodes, r2.Nodes) {
		t.Errorf("node order non-deterministic: %v vs %v", nodeIDs(r1.Nodes), nodeIDs(r2.Nodes))
	}
	if !sameEdgeIDs(r1.Edges, r2.Edges) {
		t.Errorf("edge order non-deterministic")
	}
	if !reflect.DeepEqual(nodeIDs(r1.Nodes), []string{"a", "b", "c"}) {
		t.Errorf("sort order wrong: %v", nodeIDs(r1.Nodes))
	}
}

func nodeIDs(ns []*model.CodeNode) []string {
	out := make([]string, len(ns))
	for i, n := range ns {
		out[i] = n.ID
	}
	return out
}

func sameNodeIDs(a, b []*model.CodeNode) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i].ID != b[i].ID {
			return false
		}
	}
	return true
}

func sameEdgeIDs(a, b []*model.CodeEdge) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i].ID != b[i].ID {
			return false
		}
	}
	return true
}
