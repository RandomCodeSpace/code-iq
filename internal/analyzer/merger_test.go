package analyzer

import (
	"reflect"
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

// TestGraphBuilderDedup_HigherConfidenceWins — Phase 1 plan §1.6.
// Two detectors emit the same node ID with different Confidence levels.
// The merged node keeps the higher-confidence one's Confidence + Source.
func TestGraphBuilderDedup_HigherConfidenceWins(t *testing.T) {
	gb := NewGraphBuilder()

	lex := model.NewCodeNode("class:Foo", model.NodeClass, "Foo")
	lex.Confidence = model.ConfidenceLexical
	lex.Source = "ClassHierarchyDetector"
	lex.FQN = "" // missing, should not clobber the SYNTACTIC one

	syn := model.NewCodeNode("class:Foo", model.NodeClass, "Foo")
	syn.Confidence = model.ConfidenceSyntactic
	syn.Source = "SpringRestDetector"
	syn.FQN = "com.example.Foo"
	syn.Properties["framework"] = "spring_boot"

	// Order: low-confidence first, then high. Merger must pick high.
	gb.Add(&detector.Result{Nodes: []*model.CodeNode{lex}})
	gb.Add(&detector.Result{Nodes: []*model.CodeNode{syn}})

	snap := gb.Snapshot()
	if len(snap.Nodes) != 1 {
		t.Fatalf("expected 1 deduped node, got %d", len(snap.Nodes))
	}
	got := snap.Nodes[0]
	if got.Confidence != model.ConfidenceSyntactic {
		t.Errorf("confidence = %v, want SYNTACTIC", got.Confidence)
	}
	if got.Source != "SpringRestDetector" {
		t.Errorf("source = %q, want SpringRestDetector (higher-confidence)", got.Source)
	}
	if got.FQN != "com.example.Foo" {
		t.Errorf("fqn = %q, want com.example.Foo (filled from higher-confidence)", got.FQN)
	}
	if got.Properties["framework"] != "spring_boot" {
		t.Errorf("framework property dropped: %v", got.Properties)
	}
}

// TestGraphBuilderDedup_AnnotationsUnioned — annotations from both emissions
// merge and sort deterministically.
func TestGraphBuilderDedup_AnnotationsUnioned(t *testing.T) {
	gb := NewGraphBuilder()
	a := model.NewCodeNode("svc:X", model.NodeClass, "X")
	a.Annotations = []string{"@Service", "@Transactional"}
	b := model.NewCodeNode("svc:X", model.NodeClass, "X")
	b.Annotations = []string{"@RestController", "@Service"} // overlap on @Service

	gb.Add(&detector.Result{Nodes: []*model.CodeNode{a}})
	gb.Add(&detector.Result{Nodes: []*model.CodeNode{b}})

	snap := gb.Snapshot()
	if len(snap.Nodes) != 1 {
		t.Fatalf("expected 1 node, got %d", len(snap.Nodes))
	}
	got := snap.Nodes[0].Annotations
	want := []string{"@RestController", "@Service", "@Transactional"}
	sort.Strings(got)
	if !reflect.DeepEqual(got, want) {
		t.Errorf("annotations = %v, want %v", got, want)
	}
}

// TestGraphBuilderDedup_PropertiesMergeNonClobber — incoming wins only when
// existing's value is nil/missing.
func TestGraphBuilderDedup_PropertiesMergeNonClobber(t *testing.T) {
	gb := NewGraphBuilder()
	hi := model.NewCodeNode("n", model.NodeClass, "n")
	hi.Confidence = model.ConfidenceSyntactic
	hi.Properties["framework"] = "spring_boot"
	hi.Properties["only_on_hi"] = "v1"

	lo := model.NewCodeNode("n", model.NodeClass, "n")
	lo.Confidence = model.ConfidenceLexical
	lo.Properties["framework"] = "WRONG_GUESS"
	lo.Properties["only_on_lo"] = "v2"

	gb.Add(&detector.Result{Nodes: []*model.CodeNode{hi}})
	gb.Add(&detector.Result{Nodes: []*model.CodeNode{lo}})

	got := gb.Snapshot().Nodes[0].Properties
	if got["framework"] != "spring_boot" {
		t.Errorf("framework clobbered: %v", got["framework"])
	}
	if got["only_on_hi"] != "v1" || got["only_on_lo"] != "v2" {
		t.Errorf("union failed: %v", got)
	}
}

// TestGraphBuilderEdgeDedup_ByKey — Phase 1 plan §1.2.
// Same (sourceID, targetID, kind) emitted twice via different edge IDs
// collapses to one edge in the snapshot.
func TestGraphBuilderEdgeDedup_ByKey(t *testing.T) {
	gb := NewGraphBuilder()
	n1 := model.NewCodeNode("a", model.NodeClass, "a")
	n2 := model.NewCodeNode("b", model.NodeClass, "b")

	e1 := model.NewCodeEdge("e1", model.EdgeCalls, "a", "b")
	e1.Confidence = model.ConfidenceLexical
	e2 := model.NewCodeEdge("e2-different-id", model.EdgeCalls, "a", "b")
	e2.Confidence = model.ConfidenceSyntactic

	gb.Add(&detector.Result{Nodes: []*model.CodeNode{n1, n2}, Edges: []*model.CodeEdge{e1, e2}})

	snap := gb.Snapshot()
	if len(snap.Edges) != 1 {
		t.Fatalf("expected 1 edge after (src,tgt,kind) dedup, got %d", len(snap.Edges))
	}
	if snap.Edges[0].Confidence != model.ConfidenceSyntactic {
		t.Errorf("dedup picked lower-confidence edge: %v", snap.Edges[0].Confidence)
	}
}

// TestGraphBuilderEdgeDedup_DifferentKindKept — same (src,tgt) but different
// EdgeKind must stay separate.
func TestGraphBuilderEdgeDedup_DifferentKindKept(t *testing.T) {
	gb := NewGraphBuilder()
	n1 := model.NewCodeNode("a", model.NodeClass, "a")
	n2 := model.NewCodeNode("b", model.NodeClass, "b")

	e1 := model.NewCodeEdge("e1", model.EdgeCalls, "a", "b")
	e2 := model.NewCodeEdge("e2", model.EdgeImports, "a", "b")

	gb.Add(&detector.Result{Nodes: []*model.CodeNode{n1, n2}, Edges: []*model.CodeEdge{e1, e2}})

	snap := gb.Snapshot()
	if len(snap.Edges) != 2 {
		t.Fatalf("expected 2 edges (different kinds), got %d", len(snap.Edges))
	}
}

// TestGraphBuilderEdgeDedup_PropertiesUnioned — properties from both emissions
// merge with non-clobber semantics.
func TestGraphBuilderEdgeDedup_PropertiesUnioned(t *testing.T) {
	gb := NewGraphBuilder()
	n1 := model.NewCodeNode("a", model.NodeClass, "a")
	n2 := model.NewCodeNode("b", model.NodeClass, "b")

	e1 := model.NewCodeEdge("e1", model.EdgeCalls, "a", "b")
	e1.Confidence = model.ConfidenceSyntactic
	e1.Properties["a_only"] = 1
	e2 := model.NewCodeEdge("e2", model.EdgeCalls, "a", "b")
	e2.Properties["a_only"] = "WRONG"
	e2.Properties["b_only"] = 2

	gb.Add(&detector.Result{Nodes: []*model.CodeNode{n1, n2}, Edges: []*model.CodeEdge{e1, e2}})

	snap := gb.Snapshot()
	got := snap.Edges[0].Properties
	if got["a_only"] != 1 {
		t.Errorf("a_only clobbered: %v", got["a_only"])
	}
	if got["b_only"] != 2 {
		t.Errorf("b_only not unioned: %v", got["b_only"])
	}
}

// TestGraphBuilderStats_DedupAndDropCounts — Phase 1 plan §1.5.
// Stats expose how many duplicate nodes/edges collapsed and how many
// phantom edges (missing endpoints) were dropped.
func TestGraphBuilderStats_DedupAndDropCounts(t *testing.T) {
	gb := NewGraphBuilder()
	// Two emissions of the same node → 1 deduped node.
	a := model.NewCodeNode("a", model.NodeClass, "a")
	a2 := model.NewCodeNode("a", model.NodeClass, "a")
	b := model.NewCodeNode("b", model.NodeClass, "b")
	// Two emissions of the same edge → 1 deduped edge.
	e1 := model.NewCodeEdge("e1", model.EdgeCalls, "a", "b")
	e2 := model.NewCodeEdge("e2", model.EdgeCalls, "a", "b") // same (src,tgt,kind)
	// One phantom edge → target "z" never added.
	ePhantom := model.NewCodeEdge("p", model.EdgeCalls, "a", "z")

	gb.Add(&detector.Result{
		Nodes: []*model.CodeNode{a, a2, b},
		Edges: []*model.CodeEdge{e1, e2, ePhantom},
	})

	snap := gb.Snapshot()
	if snap.DedupedNodes != 1 {
		t.Errorf("DedupedNodes = %d, want 1", snap.DedupedNodes)
	}
	if snap.DedupedEdges != 1 {
		t.Errorf("DedupedEdges = %d, want 1", snap.DedupedEdges)
	}
	if snap.DroppedEdges != 1 {
		t.Errorf("DroppedEdges = %d, want 1", snap.DroppedEdges)
	}
	if len(snap.Nodes) != 2 {
		t.Errorf("Nodes = %d, want 2", len(snap.Nodes))
	}
	if len(snap.Edges) != 1 {
		t.Errorf("Edges = %d, want 1", len(snap.Edges))
	}
}
