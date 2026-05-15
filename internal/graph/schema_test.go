package graph_test

import (
	"path/filepath"
	"testing"

	"github.com/randomcodespace/codeiq/internal/graph"
	"github.com/randomcodespace/codeiq/internal/model"
)

// TestApplySchemaCreatesAllTables asserts ApplySchema produces the expected
// node tables (CodeNode + GraphMeta) and one rel table per EdgeKind. The
// Java side mirrors this implicitly through SDN's label-driven schema; on
// Kuzu we declare it.
func TestApplySchemaCreatesAllTables(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	if err := s.ApplySchema(); err != nil {
		t.Fatalf("ApplySchema: %v", err)
	}

	rows, err := s.Cypher("CALL SHOW_TABLES() RETURN name, type")
	if err != nil {
		t.Fatalf("show tables: %v", err)
	}
	nodeTables := map[string]bool{}
	relTables := 0
	for _, r := range rows {
		switch r["type"] {
		case "NODE":
			name, _ := r["name"].(string)
			nodeTables[name] = true
		case "REL":
			relTables++
		}
	}
	if !nodeTables["CodeNode"] {
		t.Error("CodeNode node table missing")
	}
	if !nodeTables["GraphMeta"] {
		t.Error("GraphMeta node table missing")
	}
	if len(nodeTables) != 2 {
		t.Errorf("want 2 node tables (CodeNode, GraphMeta), got %d: %v", len(nodeTables), nodeTables)
	}
	if relTables != len(model.AllEdgeKinds()) {
		t.Errorf("want %d rel tables, got %d", len(model.AllEdgeKinds()), relTables)
	}
}

// TestApplySchemaIsIdempotent — re-running on an existing database is a
// no-op (uses CREATE ... IF NOT EXISTS).
func TestApplySchemaIsIdempotent(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatalf("first: %v", err)
	}
	if err := s.ApplySchema(); err != nil {
		t.Fatalf("second: %v", err)
	}
}
