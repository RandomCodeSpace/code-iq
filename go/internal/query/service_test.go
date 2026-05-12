package query_test

import (
	"path/filepath"
	"reflect"
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/graph"
	"github.com/randomcodespace/codeiq/go/internal/model"
	"github.com/randomcodespace/codeiq/go/internal/query"
)

// serviceFixture seeds the canonical 6-node graph the plan describes:
//   A ─[depends_on]─▶ B
//   A ─[produces]──▶ B
//   D ─[depends_on]─▶ B
//   D ─[produces]──▶ B
//   B ─[calls]────▶ A         (cycle A→B→A with the next edge)
//   D ─[calls]────▶ A
//   B ─[depends_on]─▶ C
//   B ─[calls]────▶ C         (so path A→B→C uses edges in same direction)
//   F ─[consumes]─▶ B
//   E is isolated (dead-code candidate)
//
// All 6 nodes are CLASS kind so dead-code filters on kind work cleanly.
func serviceFixture(t *testing.T) (*graph.Store, *query.Service) {
	t.Helper()
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}

	nodes := []*model.CodeNode{
		{ID: "A", Kind: model.NodeClass, Label: "A", Layer: model.LayerBackend},
		{ID: "B", Kind: model.NodeClass, Label: "B", Layer: model.LayerBackend},
		{ID: "C", Kind: model.NodeClass, Label: "C", Layer: model.LayerBackend},
		{ID: "D", Kind: model.NodeClass, Label: "D", Layer: model.LayerBackend},
		{ID: "E", Kind: model.NodeClass, Label: "E", Layer: model.LayerBackend}, // dead
		{ID: "F", Kind: model.NodeClass, Label: "F", Layer: model.LayerBackend},
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatal(err)
	}

	edges := []*model.CodeEdge{
		// A → B
		{ID: "e1", Kind: model.EdgeDependsOn, SourceID: "A", TargetID: "B"},
		{ID: "e2", Kind: model.EdgeProduces, SourceID: "A", TargetID: "B"},
		// D → B
		{ID: "e3", Kind: model.EdgeDependsOn, SourceID: "D", TargetID: "B"},
		{ID: "e4", Kind: model.EdgeProduces, SourceID: "D", TargetID: "B"},
		// B → A (cycle leg)
		{ID: "e5", Kind: model.EdgeCalls, SourceID: "B", TargetID: "A"},
		// D → A
		{ID: "e6", Kind: model.EdgeCalls, SourceID: "D", TargetID: "A"},
		// B → C
		{ID: "e7", Kind: model.EdgeDependsOn, SourceID: "B", TargetID: "C"},
		{ID: "e8", Kind: model.EdgeCalls, SourceID: "B", TargetID: "C"},
		// F → B (consumer)
		{ID: "e9", Kind: model.EdgeConsumes, SourceID: "F", TargetID: "B"},
	}
	if err := s.BulkLoadEdges(edges); err != nil {
		t.Fatal(err)
	}
	return s, query.NewService(s)
}

func idsOf(nodes []*model.CodeNode) []string {
	out := make([]string, len(nodes))
	for i, n := range nodes {
		out[i] = n.ID
	}
	sort.Strings(out)
	return out
}

func TestFindConsumers(t *testing.T) {
	_, svc := serviceFixture(t)
	got, err := svc.FindConsumers("B")
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"F"}; !reflect.DeepEqual(idsOf(got), want) {
		t.Fatalf("want %v, got %v", want, idsOf(got))
	}
}

func TestFindProducers(t *testing.T) {
	_, svc := serviceFixture(t)
	got, err := svc.FindProducers("B")
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"A", "D"}; !reflect.DeepEqual(idsOf(got), want) {
		t.Fatalf("want %v, got %v", want, idsOf(got))
	}
}

func TestFindCallers(t *testing.T) {
	_, svc := serviceFixture(t)
	got, err := svc.FindCallers("A")
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"B", "D"}; !reflect.DeepEqual(idsOf(got), want) {
		t.Fatalf("want %v, got %v", want, idsOf(got))
	}
}

func TestFindDependencies(t *testing.T) {
	_, svc := serviceFixture(t)
	got, err := svc.FindDependencies("A")
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"B"}; !reflect.DeepEqual(idsOf(got), want) {
		t.Fatalf("want %v, got %v", want, idsOf(got))
	}
}

func TestFindDependents(t *testing.T) {
	_, svc := serviceFixture(t)
	got, err := svc.FindDependents("B")
	if err != nil {
		t.Fatal(err)
	}
	if want := []string{"A", "D"}; !reflect.DeepEqual(idsOf(got), want) {
		t.Fatalf("want %v, got %v", want, idsOf(got))
	}
}

func TestFindShortestPath(t *testing.T) {
	_, svc := serviceFixture(t)
	got, err := svc.FindShortestPath("A", "C")
	if err != nil {
		t.Fatal(err)
	}
	// Path A → B → C through any directed edge.
	if want := []string{"A", "B", "C"}; !reflect.DeepEqual(got, want) {
		t.Fatalf("want %v, got %v", want, got)
	}
}

func TestFindShortestPathMissing(t *testing.T) {
	_, svc := serviceFixture(t)
	got, err := svc.FindShortestPath("A", "E") // E isolated
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 0 {
		t.Fatalf("want empty, got %v", got)
	}
}

func TestFindCyclesIncludesABA(t *testing.T) {
	_, svc := serviceFixture(t)
	cycles, err := svc.FindCycles(100)
	if err != nil {
		t.Fatal(err)
	}
	if len(cycles) == 0 {
		t.Fatalf("expected at least one cycle, got none")
	}
	// At least one cycle must start and end with the same id ∈ {A, B}.
	// A → B → A path = [A, B, A]; B → A → B path = [B, A, B].
	found := false
	for _, c := range cycles {
		if len(c) >= 3 && c[0] == c[len(c)-1] && (c[0] == "A" || c[0] == "B") {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("no A↔B cycle in cycles: %v", cycles)
	}
}

func TestFindDeadCode(t *testing.T) {
	_, svc := serviceFixture(t)
	dead, err := svc.FindDeadCode([]string{"class"}, 100)
	if err != nil {
		t.Fatal(err)
	}
	ids := idsOf(dead)
	// Dead-code candidates have NO incoming semantic edge:
	//   E: isolated.
	//   D: only outgoing edges (→ A, → B).
	//   F: only outgoing edge (→ B as consumer).
	// A / B / C all have incoming CALLS or DEPENDS_ON edges, so they're live.
	// This matches the Java algorithm exactly — the plan-spec example
	// expected "E only" but D and F genuinely have no incoming semantics.
	if want := []string{"D", "E", "F"}; !reflect.DeepEqual(ids, want) {
		t.Fatalf("want %v, got %v", want, ids)
	}
}

func TestFindDeadCodeDefaultKinds(t *testing.T) {
	// Empty kinds → default kinds list (class, method, interface, ...).
	// Still surfaces the same D / E / F set.
	_, svc := serviceFixture(t)
	dead, err := svc.FindDeadCode(nil, 100)
	if err != nil {
		t.Fatal(err)
	}
	ids := idsOf(dead)
	if want := []string{"D", "E", "F"}; !reflect.DeepEqual(ids, want) {
		t.Fatalf("want %v, got %v", want, ids)
	}
}
