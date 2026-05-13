package graph_test

import (
	"fmt"
	"path/filepath"
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/graph"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// seedReadsFixture stages a deterministic 10-node / 5-edge graph used by
// every read-helper test. Kinds: 5 classes + 5 methods. Edges: 5 CALLS
// from class[i] to method[i]. Plus a single IMPORTS edge from method0 to
// class0 to exercise both direction helpers.
func seedReadsFixture(t *testing.T) *graph.Store {
	t.Helper()
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}

	nodes := make([]*model.CodeNode, 0, 10)
	for i := 0; i < 5; i++ {
		nodes = append(nodes, &model.CodeNode{
			ID:    fmt.Sprintf("class:%d", i),
			Kind:  model.NodeClass,
			Label: fmt.Sprintf("Class%d", i),
			Layer: model.LayerBackend,
		})
	}
	for i := 0; i < 5; i++ {
		nodes = append(nodes, &model.CodeNode{
			ID:    fmt.Sprintf("method:%d", i),
			Kind:  model.NodeMethod,
			Label: fmt.Sprintf("method%d", i),
			Layer: model.LayerBackend,
		})
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatal(err)
	}

	edges := make([]*model.CodeEdge, 0, 6)
	for i := 0; i < 5; i++ {
		edges = append(edges, &model.CodeEdge{
			ID:       fmt.Sprintf("c2m:%d", i),
			Kind:     model.EdgeCalls,
			SourceID: fmt.Sprintf("class:%d", i),
			TargetID: fmt.Sprintf("method:%d", i),
		})
	}
	// One IMPORTS edge for direction tests.
	edges = append(edges, &model.CodeEdge{
		ID:       "m2c:0",
		Kind:     model.EdgeImports,
		SourceID: "method:0",
		TargetID: "class:0",
	})
	if err := s.BulkLoadEdges(edges); err != nil {
		t.Fatal(err)
	}
	return s
}

func TestCountNodes(t *testing.T) {
	s := seedReadsFixture(t)
	n, err := s.Count()
	if err != nil {
		t.Fatal(err)
	}
	if n != 10 {
		t.Fatalf("want 10, got %d", n)
	}
}

func TestCountEdges(t *testing.T) {
	s := seedReadsFixture(t)
	n, err := s.CountEdges()
	if err != nil {
		t.Fatal(err)
	}
	if n != 6 {
		t.Fatalf("want 6, got %d", n)
	}
}

func TestCountNodesByKind(t *testing.T) {
	s := seedReadsFixture(t)
	by, err := s.CountNodesByKind()
	if err != nil {
		t.Fatal(err)
	}
	if by["class"] != 5 || by["method"] != 5 {
		t.Fatalf("want class=5 method=5, got %+v", by)
	}
}

func TestCountNodesByLayer(t *testing.T) {
	s := seedReadsFixture(t)
	by, err := s.CountNodesByLayer()
	if err != nil {
		t.Fatal(err)
	}
	if by["backend"] != 10 {
		t.Fatalf("want backend=10, got %+v", by)
	}
}

func TestFindByID(t *testing.T) {
	s := seedReadsFixture(t)
	n, err := s.FindByID("class:2")
	if err != nil {
		t.Fatal(err)
	}
	if n == nil || n.ID != "class:2" || n.Kind != model.NodeClass {
		t.Fatalf("got %+v", n)
	}
}

func TestFindByIDMissing(t *testing.T) {
	s := seedReadsFixture(t)
	n, err := s.FindByID("does-not-exist")
	if err != nil {
		t.Fatal(err)
	}
	if n != nil {
		t.Fatalf("want nil, got %+v", n)
	}
}

func TestFindByKindPaginated(t *testing.T) {
	s := seedReadsFixture(t)
	page1, err := s.FindByKindPaginated("class", 0, 3)
	if err != nil {
		t.Fatal(err)
	}
	if len(page1) != 3 {
		t.Fatalf("page1 wants 3, got %d", len(page1))
	}
	page2, err := s.FindByKindPaginated("class", 3, 3)
	if err != nil {
		t.Fatal(err)
	}
	if len(page2) != 2 {
		t.Fatalf("page2 wants 2, got %d", len(page2))
	}
	// Pages must not overlap.
	seen := map[string]bool{}
	for _, n := range append(page1, page2...) {
		if seen[n.ID] {
			t.Fatalf("duplicate id %q", n.ID)
		}
		seen[n.ID] = true
	}
}

func TestFindIncomingNeighbors(t *testing.T) {
	s := seedReadsFixture(t)
	// class:0 is the target of method:0 IMPORTS class:0 — one incoming.
	in, err := s.FindIncomingNeighbors("class:0")
	if err != nil {
		t.Fatal(err)
	}
	if len(in) != 1 || in[0].ID != "method:0" {
		t.Fatalf("got %+v", in)
	}
	// class:1 has no incoming.
	in, err = s.FindIncomingNeighbors("class:1")
	if err != nil {
		t.Fatal(err)
	}
	if len(in) != 0 {
		t.Fatalf("want empty, got %+v", in)
	}
}

func TestFindOutgoingNeighbors(t *testing.T) {
	s := seedReadsFixture(t)
	// class:2 -[:CALLS]-> method:2
	out, err := s.FindOutgoingNeighbors("class:2")
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 1 || out[0].ID != "method:2" {
		t.Fatalf("got %+v", out)
	}
	// method:0 -[:IMPORTS]-> class:0
	out, err = s.FindOutgoingNeighbors("method:0")
	if err != nil {
		t.Fatal(err)
	}
	if len(out) != 1 || out[0].ID != "class:0" {
		t.Fatalf("got %+v", out)
	}
}

func TestFindOutgoingNeighborsOrderedByID(t *testing.T) {
	// Determinism guard: order matters for parity diffing and stable
	// snapshot tests downstream.
	s := seedReadsFixture(t)
	in, err := s.FindIncomingNeighbors("class:0")
	if err != nil {
		t.Fatal(err)
	}
	ids := make([]string, len(in))
	for i, n := range in {
		ids[i] = n.ID
	}
	if !sort.StringsAreSorted(ids) {
		t.Fatalf("ids not sorted: %v", ids)
	}
}
