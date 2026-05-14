package parity

import (
	"encoding/json"
	"path/filepath"
	"testing"

	"github.com/randomcodespace/codeiq/internal/graph"
)

// TestDumpKuzuEmptyStore verifies DumpKuzu against a fresh-but-empty store
// produces a well-formed JSON envelope with empty "nodes"/"edges" arrays.
// Catches regressions where Cypher errors would silently propagate to nil
// arrays in the JSON.
func TestDumpKuzuEmptyStore(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "empty.kuzu")
	s, err := graph.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	s.Close()

	out, err := DumpKuzu(dir)
	if err != nil {
		t.Fatalf("DumpKuzu on empty store: %v", err)
	}
	var got map[string]any
	if err := json.Unmarshal(out, &got); err != nil {
		t.Fatalf("output is not JSON: %v\n%s", err, out)
	}
	nodes, ok := got["nodes"].([]any)
	if !ok {
		t.Fatalf("missing nodes key or wrong type: %T", got["nodes"])
	}
	edges, ok := got["edges"].([]any)
	if !ok {
		t.Fatalf("missing edges key or wrong type: %T", got["edges"])
	}
	if len(nodes) != 0 {
		t.Errorf("expected 0 nodes, got %d", len(nodes))
	}
	if len(edges) != 0 {
		t.Errorf("expected 0 edges, got %d", len(edges))
	}
}

// TestDumpKuzuIsDeterministic re-dumps the same empty store twice and
// asserts byte-equality.
func TestDumpKuzuIsDeterministic(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "det.kuzu")
	s, err := graph.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	s.Close()

	first, err := DumpKuzu(dir)
	if err != nil {
		t.Fatal(err)
	}
	second, err := DumpKuzu(dir)
	if err != nil {
		t.Fatal(err)
	}
	if string(first) != string(second) {
		t.Fatalf("non-deterministic dump:\nfirst:\n%s\n\nsecond:\n%s", first, second)
	}
}
