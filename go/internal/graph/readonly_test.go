package graph_test

import (
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/randomcodespace/codeiq/go/internal/graph"
)

// TestOpenReadOnlyRejectsWrites bootstraps a small DB with Open, closes
// it, then re-opens with OpenReadOnly and asserts reads work while
// writes are rejected at the Cypher gate.
func TestOpenReadOnlyRejectsWrites(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "ro.kuzu")

	writable, err := graph.Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	if err := writable.ApplySchema(); err != nil {
		t.Fatalf("ApplySchema: %v", err)
	}
	if err := writable.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	ro, err := graph.OpenReadOnly(dir, 30*time.Second)
	if err != nil {
		t.Fatalf("OpenReadOnly: %v", err)
	}
	defer ro.Close()
	if !ro.IsReadOnly() {
		t.Fatalf("expected IsReadOnly true")
	}

	if _, err := ro.Cypher(`MATCH (n:CodeNode) RETURN count(n) AS c`); err != nil {
		t.Fatalf("read failed in read-only store: %v", err)
	}

	if _, err := ro.Cypher(`CREATE (:CodeNode {id: 'x', kind: 'k', label: 'l'})`); err == nil {
		t.Fatalf("expected write to fail in read-only store")
	} else if !strings.Contains(err.Error(), "read-only") {
		t.Fatalf("write error = %v, want 'read-only' substring", err)
	}
}

// TestMutationKeyword tables the keyword detector.
func TestMutationKeyword(t *testing.T) {
	cases := []struct {
		name string
		q    string
		want string
	}{
		{"plain read", "MATCH (n) RETURN n", ""},
		{"create", "CREATE (:X)", "CREATE"},
		{"delete", "MATCH (n) DELETE n", "DELETE"},
		{"detach delete", "MATCH (n) DETACH DELETE n", "DETACH"},
		{"set", "MATCH (n) SET n.k = 1", "SET"},
		{"remove", "MATCH (n) REMOVE n.k", "REMOVE"},
		{"merge", "MERGE (:X)", "MERGE"},
		{"drop", "DROP TABLE X", "DROP"},
		{"load csv", "LOAD CSV FROM 'x' INTO X", "LOAD CSV"},
		{"copy", "COPY X FROM 'y'", "COPY"},
		{"lowercase create", "create (:X)", "create"},
		{"comment hidden create", "MATCH (n) RETURN n /* CREATE */", ""},
		{"line comment hidden create", "MATCH (n) RETURN n // CREATE", ""},
		{"created_at column passes", "MATCH (n) WHERE n.created_at > 0 RETURN n", ""},
		{"call db. allowed", "CALL db.indexes() YIELD name RETURN name", ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := graph.MutationKeyword(c.q)
			// Compare case-insensitively for clarity, since the matched
			// substring preserves case from the input.
			if !strings.EqualFold(got, c.want) {
				t.Fatalf("MutationKeyword(%q) = %q, want %q", c.q, got, c.want)
			}
		})
	}
}

// TestCypherRowsTruncation runs a query that returns more rows than the
// cap and asserts the truncated flag is set without LIMIT being injected.
func TestCypherRowsTruncation(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "rows.kuzu")
	s, err := graph.Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatalf("ApplySchema: %v", err)
	}
	// Seed 5 nodes.
	for i := 0; i < 5; i++ {
		if _, err := s.Cypher(`CREATE (:CodeNode {id: $id, kind: 'k', label: 'l'})`,
			map[string]any{"id": string(rune('a' + i))}); err != nil {
			t.Fatalf("seed %d: %v", i, err)
		}
	}
	rows, truncated, err := s.CypherRows(`MATCH (n:CodeNode) RETURN n.id AS id`, nil, 3)
	if err != nil {
		t.Fatalf("CypherRows: %v", err)
	}
	if len(rows) != 3 {
		t.Fatalf("rows = %d, want 3", len(rows))
	}
	if !truncated {
		t.Fatalf("expected truncated=true (5 rows > cap 3)")
	}
}
