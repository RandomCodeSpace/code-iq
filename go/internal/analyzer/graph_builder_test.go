package analyzer

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestSnapshotReleasesDedupMaps(t *testing.T) {
	gb := NewGraphBuilder()
	gb.Add(&detector.Result{
		Nodes: []*model.CodeNode{model.NewCodeNode("x", model.NodeClass, "X")},
		Edges: []*model.CodeEdge{{ID: "e:x:x", SourceID: "x", TargetID: "x", Kind: model.EdgeContains}},
	})
	_ = gb.Snapshot()
	if gb.nodes != nil {
		t.Errorf("Snapshot must nil GraphBuilder.nodes to allow GC; got len=%d", len(gb.nodes))
	}
	if gb.edges != nil {
		t.Errorf("Snapshot must nil GraphBuilder.edges to allow GC; got len=%d", len(gb.edges))
	}
}

func TestGraphBuilderDeduplicatesByID(t *testing.T) {
	gb := NewGraphBuilder()
	n1 := model.NewCodeNode("a", model.NodeClass, "A")
	n2 := model.NewCodeNode("a", model.NodeClass, "A") // duplicate
	gb.Add(&detector.Result{Nodes: []*model.CodeNode{n1, n2}})
	snap := gb.Snapshot()
	if len(snap.Nodes) != 1 {
		t.Fatalf("expected 1 deduped node, got %d", len(snap.Nodes))
	}
}

func TestGraphBuilderSortsForDeterminism(t *testing.T) {
	gb := NewGraphBuilder()
	gb.Add(&detector.Result{
		Nodes: []*model.CodeNode{
			model.NewCodeNode("z", model.NodeClass, "Z"),
			model.NewCodeNode("a", model.NodeClass, "A"),
			model.NewCodeNode("m", model.NodeClass, "M"),
		},
	})
	snap := gb.Snapshot()
	want := []string{"a", "m", "z"}
	for i, n := range snap.Nodes {
		if n.ID != want[i] {
			t.Errorf("ID[%d] = %q, want %q", i, n.ID, want[i])
		}
	}
}

func TestGraphBuilderDropsEdgesWithMissingSourceOrTarget(t *testing.T) {
	gb := NewGraphBuilder()
	gb.Add(&detector.Result{
		Nodes: []*model.CodeNode{model.NewCodeNode("a", model.NodeClass, "A")},
		Edges: []*model.CodeEdge{
			model.NewCodeEdge("a->b", model.EdgeCalls, "a", "b"), // b missing
			model.NewCodeEdge("a->ext", model.EdgeImports, "a", "ext:django"),
		},
	})
	gb.Add(&detector.Result{
		Nodes: []*model.CodeNode{model.NewCodeNode("ext:django", model.NodeModule, "django")},
	})
	snap := gb.Snapshot()
	if len(snap.Edges) != 1 || snap.Edges[0].ID != "a->ext" {
		t.Fatalf("missing-target edges should be dropped, got %+v", snap.Edges)
	}
}

func TestGraphBuilderNodesBeforeEdges(t *testing.T) {
	// Snapshot returns nodes already populated when edges are walked, so a
	// graph-store flush can write in two phases (nodes, then edges) without
	// reordering.
	gb := NewGraphBuilder()
	gb.Add(&detector.Result{
		Nodes: []*model.CodeNode{
			model.NewCodeNode("src", model.NodeClass, "S"),
			model.NewCodeNode("tgt", model.NodeClass, "T"),
		},
		Edges: []*model.CodeEdge{
			model.NewCodeEdge("src->tgt", model.EdgeCalls, "src", "tgt"),
		},
	})
	snap := gb.Snapshot()
	if len(snap.Nodes) != 2 || len(snap.Edges) != 1 {
		t.Fatalf("snapshot mismatch: %+v", snap)
	}
}
