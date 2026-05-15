package graph_test

import (
	"path/filepath"
	"testing"

	"github.com/randomcodespace/codeiq/internal/graph"
	"github.com/randomcodespace/codeiq/internal/model"
)

func openSchemaStore(t *testing.T) *graph.Store {
	t.Helper()
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	if err := s.ApplySchema(); err != nil {
		s.Close()
		t.Fatal(err)
	}
	return s
}

func countCodeNodes(t *testing.T, s *graph.Store) int64 {
	t.Helper()
	rows, err := s.Cypher("MATCH (n:CodeNode) RETURN count(n) AS c")
	if err != nil {
		t.Fatal(err)
	}
	return rows[0]["c"].(int64)
}

func TestRemoveFileDeletesAllNodesForPath(t *testing.T) {
	s := openSchemaStore(t)
	defer s.Close()

	nodes := []*model.CodeNode{
		{ID: "n1", Kind: model.NodeClass, Label: "A", FilePath: "A.java", Layer: model.LayerBackend},
		{ID: "n2", Kind: model.NodeMethod, Label: "foo", FilePath: "A.java", Layer: model.LayerBackend},
		{ID: "n3", Kind: model.NodeClass, Label: "B", FilePath: "B.java", Layer: model.LayerBackend},
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatal(err)
	}
	if err := s.RemoveFile("A.java"); err != nil {
		t.Fatalf("RemoveFile: %v", err)
	}
	rows, err := s.Cypher("MATCH (n:CodeNode) RETURN n.id AS id ORDER BY id")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 {
		t.Fatalf("want 1 remaining (n3), got %d: %v", len(rows), rows)
	}
	if rows[0]["id"] != "n3" {
		t.Fatalf("wrong survivor: %v", rows[0])
	}
}

func TestRemoveFileIsIdempotent(t *testing.T) {
	s := openSchemaStore(t)
	defer s.Close()
	if err := s.RemoveFile("never-existed.java"); err != nil {
		t.Fatalf("RemoveFile on missing: %v", err)
	}
}

func TestRemoveFileDeletesIncidentEdges(t *testing.T) {
	s := openSchemaStore(t)
	defer s.Close()
	nodes := []*model.CodeNode{
		{ID: "n1", Kind: model.NodeClass, Label: "A", FilePath: "A.java"},
		{ID: "n2", Kind: model.NodeClass, Label: "B", FilePath: "B.java"},
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatal(err)
	}
	edges := []*model.CodeEdge{
		{ID: "n1->n2", Kind: model.EdgeCalls, SourceID: "n1", TargetID: "n2",
			Confidence: model.ConfidenceSyntactic},
	}
	if err := s.BulkLoadEdges(edges); err != nil {
		t.Fatal(err)
	}
	if err := s.RemoveFile("A.java"); err != nil {
		t.Fatalf("RemoveFile: %v", err)
	}
	rows, err := s.Cypher("MATCH ()-[r:CALLS]->() RETURN count(r) AS c")
	if err != nil {
		t.Fatal(err)
	}
	if rows[0]["c"].(int64) != 0 {
		t.Fatalf("edge to deleted node survived: %v", rows[0])
	}
}

func TestInsertFileAddsNodesAndEdges(t *testing.T) {
	s := openSchemaStore(t)
	defer s.Close()
	nodes := []*model.CodeNode{
		{ID: "j:file:C.java", Kind: model.NodeModule, Label: "C.java", FilePath: "C.java"},
		{ID: "j:C.java:class:C", Kind: model.NodeClass, Label: "C", FilePath: "C.java"},
	}
	edges := []*model.CodeEdge{{
		ID: "j:file:C.java->j:C.java:class:C", Kind: model.EdgeContains,
		SourceID: "j:file:C.java", TargetID: "j:C.java:class:C",
		Confidence: model.ConfidenceSyntactic, Source: "test",
	}}
	if err := s.InsertFile("C.java", nodes, edges); err != nil {
		t.Fatalf("InsertFile: %v", err)
	}
	rows, err := s.Cypher("MATCH (n:CodeNode) WHERE n.file_path = 'C.java' RETURN count(n) AS c")
	if err != nil {
		t.Fatal(err)
	}
	if got := rows[0]["c"].(int64); got != 2 {
		t.Fatalf("want 2 nodes, got %v", rows[0]["c"])
	}
	rels, err := s.Cypher("MATCH ()-[r:CONTAINS]->() RETURN count(r) AS c")
	if err != nil {
		t.Fatal(err)
	}
	if got := rels[0]["c"].(int64); got != 1 {
		t.Fatalf("want 1 CONTAINS edge, got %v", rels[0]["c"])
	}
}

func TestInsertFileEmptyIsNoop(t *testing.T) {
	s := openSchemaStore(t)
	defer s.Close()
	if err := s.InsertFile("empty.java", nil, nil); err != nil {
		t.Fatalf("InsertFile with empty input should be no-op, got: %v", err)
	}
	if countCodeNodes(t, s) != 0 {
		t.Fatal("empty insert created phantom nodes")
	}
}

func TestWipeLinkerEdgesByTag(t *testing.T) {
	s := openSchemaStore(t)
	defer s.Close()
	if err := s.BulkLoadNodes([]*model.CodeNode{
		{ID: "a", Kind: model.NodeService, Label: "A"},
		{ID: "b", Kind: model.NodeService, Label: "B"},
	}); err != nil {
		t.Fatal(err)
	}
	if err := s.BulkLoadEdges([]*model.CodeEdge{
		{ID: "e-det", Kind: model.EdgeDependsOn, SourceID: "a", TargetID: "b",
			Source: "SomeDetector", Confidence: model.ConfidenceSyntactic},
		{ID: "e-lnk", Kind: model.EdgeDependsOn, SourceID: "a", TargetID: "b",
			Source: "linker:topic", Confidence: model.ConfidenceLexical},
	}); err != nil {
		t.Fatal(err)
	}
	if err := s.WipeLinkerEdges([]string{"linker:topic", "linker:entity", "linker:module_containment"}); err != nil {
		t.Fatalf("WipeLinkerEdges: %v", err)
	}
	rows, err := s.Cypher("MATCH ()-[r:DEPENDS_ON]->() RETURN r.id AS id ORDER BY r.id")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 1 {
		t.Fatalf("want 1 surviving (detector), got %d: %v", len(rows), rows)
	}
	if rows[0]["id"] != "e-det" {
		t.Fatalf("wrong edge survived: %v", rows[0])
	}
}

func TestWipeLinkerEdgesEmptySources(t *testing.T) {
	s := openSchemaStore(t)
	defer s.Close()
	// Empty sources is a no-op, not an error.
	if err := s.WipeLinkerEdges(nil); err != nil {
		t.Fatalf("WipeLinkerEdges(nil): %v", err)
	}
}

func TestWipeLinkerEdgesAlsoDropsLinkerNodes(t *testing.T) {
	s := openSchemaStore(t)
	defer s.Close()
	if err := s.BulkLoadNodes([]*model.CodeNode{
		{ID: "m:auth", Kind: model.NodeModule, Label: "auth", Source: "linker:module_containment"},
		{ID: "c:Foo", Kind: model.NodeClass, Label: "Foo", Source: "SomeDetector"},
	}); err != nil {
		t.Fatal(err)
	}
	if err := s.WipeLinkerEdges([]string{"linker:module_containment"}); err != nil {
		t.Fatal(err)
	}
	rows, _ := s.Cypher("MATCH (n:CodeNode) RETURN n.id AS id ORDER BY n.id")
	if len(rows) != 1 {
		t.Fatalf("want 1 surviving (detector node), got %d: %v", len(rows), rows)
	}
	if rows[0]["id"] != "c:Foo" {
		t.Fatalf("wrong node survived: %v", rows[0])
	}
}

func TestReplaceFileSwapsContent(t *testing.T) {
	s := openSchemaStore(t)
	defer s.Close()

	if err := s.InsertFile("D.java", []*model.CodeNode{
		{ID: "old-d", Kind: model.NodeClass, Label: "OldD", FilePath: "D.java"},
	}, nil); err != nil {
		t.Fatal(err)
	}
	if err := s.ReplaceFile("D.java",
		[]*model.CodeNode{
			{ID: "new-d", Kind: model.NodeClass, Label: "NewD", FilePath: "D.java"},
			{ID: "new-d-m", Kind: model.NodeMethod, Label: "m", FilePath: "D.java"},
		}, nil); err != nil {
		t.Fatalf("ReplaceFile: %v", err)
	}
	rows, err := s.Cypher("MATCH (n:CodeNode) WHERE n.file_path = 'D.java' RETURN n.id AS id ORDER BY n.id")
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) != 2 {
		t.Fatalf("want 2 nodes after replace, got %d: %v", len(rows), rows)
	}
	if rows[0]["id"] != "new-d" || rows[1]["id"] != "new-d-m" {
		t.Fatalf("nodes after replace: %v", rows)
	}
}
