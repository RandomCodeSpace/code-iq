package graph_test

import (
	"path/filepath"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/graph"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// TestSearchIndexHitsLabel asserts the search_index FTS hits on
// label_lower. Mirrors the Java search_index that powers /search and the
// `search_graph` MCP tool.
func TestSearchIndexHitsLabel(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	nodes := []*model.CodeNode{
		{ID: "1", Kind: model.NodeClass, Label: "UserService"},
		{ID: "2", Kind: model.NodeClass, Label: "OrderRepository"},
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatal(err)
	}
	if err := s.CreateIndexes(); err != nil {
		t.Fatalf("CreateIndexes: %v", err)
	}
	rows, err := s.SearchByLabel("userservice", 10)
	if err != nil {
		t.Fatalf("SearchByLabel: %v", err)
	}
	if len(rows) != 1 || rows[0].ID != "1" {
		t.Fatalf("rows: %+v", rows)
	}
}

// TestLexicalIndexHitsDocComment asserts the lexical_index FTS covers
// prop_lex_comment, the column LexicalEnricher writes from doc-comments
// during enrichment.
func TestLexicalIndexHitsDocComment(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	nodes := []*model.CodeNode{
		{
			ID: "1", Kind: model.NodeMethod, Label: "checkout",
			Properties: map[string]any{"lex_comment": "process checkout for shopping cart"},
		},
		{
			ID: "2", Kind: model.NodeMethod, Label: "login",
			Properties: map[string]any{"lex_comment": "authenticate the user"},
		},
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatal(err)
	}
	if err := s.CreateIndexes(); err != nil {
		t.Fatal(err)
	}
	rows, err := s.SearchLexical("shopping", 10)
	if err != nil {
		t.Fatalf("SearchLexical: %v", err)
	}
	if len(rows) != 1 || rows[0].ID != "1" {
		t.Fatalf("rows: %+v", rows)
	}
}

// TestCreateIndexesIdempotent — re-running on an existing graph must not
// error. Kuzu's CREATE_FTS_INDEX itself raises on duplicate index name; the
// helper has to swallow the "already exists" case.
func TestCreateIndexesIdempotent(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	if err := s.CreateIndexes(); err != nil {
		t.Fatalf("first: %v", err)
	}
	if err := s.CreateIndexes(); err != nil {
		t.Fatalf("second: %v", err)
	}
}
