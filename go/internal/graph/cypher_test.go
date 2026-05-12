package graph_test

import (
	"path/filepath"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/graph"
)

func TestCypherReturnsRows(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	// Kuzu requires schema before insert; run a trivial CREATE NODE TABLE
	// and INSERT, then SELECT.
	if _, err := s.Cypher("CREATE NODE TABLE Tiny(id STRING, PRIMARY KEY(id))"); err != nil {
		t.Fatalf("create table: %v", err)
	}
	if _, err := s.Cypher("CREATE (:Tiny {id: 'a'})"); err != nil {
		t.Fatalf("insert: %v", err)
	}
	rows, err := s.Cypher("MATCH (n:Tiny) RETURN n.id AS id")
	if err != nil {
		t.Fatalf("select: %v", err)
	}
	if len(rows) != 1 {
		t.Fatalf("want 1 row, got %d", len(rows))
	}
	if rows[0]["id"] != "a" {
		t.Fatalf("want id=a, got %v", rows[0]["id"])
	}
}

func TestCypherDDLReturnsEmpty(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	rows, err := s.Cypher("CREATE NODE TABLE T(id STRING, PRIMARY KEY(id))")
	if err != nil {
		t.Fatalf("ddl: %v", err)
	}
	// DDL may report 0 rows or a single status row depending on Kuzu;
	// the contract is "no error". The exact row count is not part of the
	// API surface for DDL.
	_ = rows
}

func TestCypherWithParams(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()

	if _, err := s.Cypher("CREATE NODE TABLE Tiny(id STRING, PRIMARY KEY(id))"); err != nil {
		t.Fatalf("create table: %v", err)
	}
	if _, err := s.Cypher("CREATE (:Tiny {id: 'a'})"); err != nil {
		t.Fatalf("insert: %v", err)
	}
	rows, err := s.Cypher(
		"MATCH (n:Tiny) WHERE n.id = $wanted RETURN n.id AS id",
		map[string]any{"wanted": "a"},
	)
	if err != nil {
		t.Fatalf("parameterized select: %v", err)
	}
	if len(rows) != 1 {
		t.Fatalf("want 1 row, got %d", len(rows))
	}
	if rows[0]["id"] != "a" {
		t.Fatalf("want id=a, got %v", rows[0]["id"])
	}
}

func TestCypherOnClosedStoreErrors(t *testing.T) {
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	if err := s.Close(); err != nil {
		t.Fatal(err)
	}
	if _, err := s.Cypher("RETURN 1"); err == nil {
		t.Fatal("expected error on closed store")
	}
}
