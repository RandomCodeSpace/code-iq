package graph_test

import (
	"fmt"
	"path/filepath"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/graph"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// TestBulkLoadNodes1000 exercises the COPY FROM path with 1000 rows. The
// volume is intentionally non-trivial — per-node CREATE would dominate the
// enrich step at the scales we target (44K files, 100K+ nodes).
func TestBulkLoadNodes1000(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}

	nodes := make([]*model.CodeNode, 1000)
	for i := 0; i < 1000; i++ {
		nodes[i] = &model.CodeNode{
			ID:       fmt.Sprintf("n:%04d", i),
			Kind:     model.NodeClass,
			Label:    fmt.Sprintf("Class%04d", i),
			FilePath: fmt.Sprintf("src/Class%04d.java", i),
			Layer:    model.LayerBackend,
			Properties: map[string]any{
				"framework": "spring_boot",
			},
		}
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatalf("BulkLoadNodes: %v", err)
	}
	rows, err := s.Cypher("MATCH (n:CodeNode) RETURN count(n) AS c")
	if err != nil {
		t.Fatal(err)
	}
	if rows[0]["c"].(int64) != 1000 {
		t.Fatalf("want 1000 rows, got %v", rows[0]["c"])
	}
}

// TestBulkLoadNodesEmpty — passing zero nodes is a no-op, not an error.
// The CSV staging would otherwise produce an empty file Kuzu may reject.
func TestBulkLoadNodesEmpty(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	if err := s.BulkLoadNodes(nil); err != nil {
		t.Fatalf("BulkLoadNodes(nil): %v", err)
	}
	if err := s.BulkLoadNodes([]*model.CodeNode{}); err != nil {
		t.Fatalf("BulkLoadNodes([]): %v", err)
	}
}
