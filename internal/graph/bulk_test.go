package graph_test

import (
	"fmt"
	"path/filepath"
	"testing"

	"github.com/randomcodespace/codeiq/internal/graph"
	"github.com/randomcodespace/codeiq/internal/model"
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

// TestBulkLoadEdges round-trips a single edge through COPY FROM and asserts
// it materialises in the right REL table (CALLS) with the correct primary
// id property.
func TestBulkLoadEdges(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	nodes := []*model.CodeNode{
		{ID: "a", Kind: model.NodeClass, Label: "A"},
		{ID: "b", Kind: model.NodeClass, Label: "B"},
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatal(err)
	}
	edges := []*model.CodeEdge{{
		ID:         "a->b",
		Kind:       model.EdgeCalls,
		SourceID:   "a",
		TargetID:   "b",
		Confidence: model.ConfidenceSyntactic,
	}}
	if err := s.BulkLoadEdges(edges); err != nil {
		t.Fatalf("BulkLoadEdges: %v", err)
	}
	rows, err := s.Cypher("MATCH (a:CodeNode)-[r:CALLS]->(b:CodeNode) RETURN r.id AS id")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0]["id"] != "a->b" {
		t.Fatalf("rows: %v", rows)
	}
}

// TestBulkLoadEdgesGroupedByKind asserts edges are routed to the right REL
// table when mixed kinds arrive in one call.
func TestBulkLoadEdgesGroupedByKind(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	nodes := []*model.CodeNode{
		{ID: "a", Kind: model.NodeClass, Label: "A"},
		{ID: "b", Kind: model.NodeClass, Label: "B"},
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatal(err)
	}
	edges := []*model.CodeEdge{
		{ID: "ab-calls", Kind: model.EdgeCalls, SourceID: "a", TargetID: "b"},
		{ID: "ab-imports", Kind: model.EdgeImports, SourceID: "a", TargetID: "b"},
	}
	if err := s.BulkLoadEdges(edges); err != nil {
		t.Fatalf("BulkLoadEdges: %v", err)
	}
	rows, err := s.Cypher("MATCH ()-[r:CALLS]->() RETURN r.id AS id")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0]["id"] != "ab-calls" {
		t.Fatalf("CALLS rows: %v", rows)
	}
	rows, err = s.Cypher("MATCH ()-[r:IMPORTS]->() RETURN r.id AS id")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 || rows[0]["id"] != "ab-imports" {
		t.Fatalf("IMPORTS rows: %v", rows)
	}
}

// TestBulkLoadEdgesCommaInProperties is a regression test for the bug where
// Properties JSON containing commas (e.g. {"language":"python","module":"glob"})
// caused Kuzu's CSV parser to count more fields than expected and abort with
// "Copy exception: expected 6 values per row, but got more". The fix switches
// the staging file to pipe-separated (DELIM='|'), which is unambiguous because
// Go's json.Marshal never emits a '|' character.
func TestBulkLoadEdgesCommaInProperties(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	nodes := []*model.CodeNode{
		{ID: "py:file:check_structure.py", Kind: model.NodeModule, Label: "check_structure.py"},
		{ID: "py:external:glob", Kind: model.NodeExternal, Label: "glob"},
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatal(err)
	}
	edges := []*model.CodeEdge{{
		ID:         "py:file:check_structure.py->py:external:glob:imports",
		Kind:       model.EdgeImports,
		SourceID:   "py:file:check_structure.py",
		TargetID:   "py:external:glob",
		Confidence: model.ConfidenceLexical,
		Source:     "GenericImportsDetector",
		Properties: map[string]any{
			"language": "python",
			"module":   "glob",
		},
	}}
	if err := s.BulkLoadEdges(edges); err != nil {
		t.Fatalf("BulkLoadEdges with comma-bearing Properties: %v", err)
	}
	rows, err := s.Cypher("MATCH ()-[r:IMPORTS]->() RETURN r.id AS id")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 {
		t.Fatalf("want 1 IMPORTS row, got %d: %v", len(rows), rows)
	}
}

// TestBulkLoadNodesCommaInProperties is a regression test for nodes whose
// props JSON column contains commas — same root cause as the edge variant.
func TestBulkLoadNodesCommaInProperties(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	nodes := []*model.CodeNode{{
		ID:    "py:file:app.py",
		Kind:  model.NodeModule,
		Label: "app.py",
		Properties: map[string]any{
			"language": "python",
			"module":   "flask,requests,os", // value itself contains commas
		},
	}}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatalf("BulkLoadNodes with comma-bearing Properties: %v", err)
	}
	rows, err := s.Cypher("MATCH (n:CodeNode {id: 'py:file:app.py'}) RETURN n.id AS id")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 {
		t.Fatalf("want 1 node, got %d: %v", len(rows), rows)
	}
}

// TestBulkLoadEdgesPipeInTargetID is a regression test for Istio-style IDs
// that contain the field delimiter '|' literally (e.g. EDS cluster names
// "inbound|7070|tcplocal|s1tcp.none" parsed from JSON config). Go's csv.Writer
// RFC-4180-wraps such fields in '"', but Kuzu's default ESCAPE is backslash
// not doubled-quote — so without explicit QUOTE='"', ESCAPE='"' the COPY
// FROM splits the wrapped field on each interior '|' and aborts with
// "expected N values per row, but got more". Fix: explicit QUOTE/ESCAPE in
// the COPY FROM clause.
func TestBulkLoadEdgesPipeInTargetID(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	// Istio-flavoured target ID with literal pipes.
	target := "json:istio/none_cds.json:inbound|7070|tcplocal|s1tcp.none"
	nodes := []*model.CodeNode{
		{ID: "json:istio/none_cds.json", Kind: model.NodeModule, Label: "none_cds.json"},
		{ID: target, Kind: model.NodeConfigKey, Label: "inbound|7070|tcplocal|s1tcp.none"},
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatalf("BulkLoadNodes with pipe-bearing ID: %v", err)
	}
	edges := []*model.CodeEdge{{
		ID:         "json:istio/none_cds.json->" + target,
		Kind:       model.EdgeContains,
		SourceID:   "json:istio/none_cds.json",
		TargetID:   target,
		Confidence: model.ConfidenceSyntactic,
		Source:     "JsonStructureDetector",
	}}
	if err := s.BulkLoadEdges(edges); err != nil {
		t.Fatalf("BulkLoadEdges with pipe-bearing target ID: %v", err)
	}
	rows, err := s.Cypher("MATCH ()-[r:CONTAINS]->() RETURN r.id AS id")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 {
		t.Fatalf("want 1 CONTAINS row, got %d: %v", len(rows), rows)
	}
}

// TestBulkLoadEdgesEmpty — zero edges is a no-op like the node path.
func TestBulkLoadEdgesEmpty(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	if err := s.BulkLoadEdges(nil); err != nil {
		t.Fatalf("BulkLoadEdges(nil): %v", err)
	}
	if err := s.BulkLoadEdges([]*model.CodeEdge{}); err != nil {
		t.Fatalf("BulkLoadEdges([]): %v", err)
	}
}
