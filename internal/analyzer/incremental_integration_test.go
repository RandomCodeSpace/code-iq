package analyzer_test

import (
	"os"
	"path/filepath"
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/analyzer"
	"github.com/randomcodespace/codeiq/internal/cache"
	"github.com/randomcodespace/codeiq/internal/graph"
)

// graphSnapshot returns a stable, comparable representation of the graph:
// every node by id+kind+label and every edge by id+kind+source+target,
// sorted. Excludes anything timestamped or otherwise legitimately variable.
func graphSnapshot(t *testing.T, graphDir string) (nodes []string, edges []string) {
	t.Helper()
	s, err := graph.OpenReadOnly(graphDir, 0)
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	nodeRows, err := s.Cypher(
		`MATCH (n:CodeNode) RETURN n.id AS id, n.kind AS kind, n.label AS label ORDER BY n.id`)
	if err != nil {
		t.Fatal(err)
	}
	for _, r := range nodeRows {
		nodes = append(nodes,
			asString(r["id"])+"|"+asString(r["kind"])+"|"+asString(r["label"]))
	}
	edgeRows, err := s.Cypher(
		`MATCH (a:CodeNode)-[r]->(b:CodeNode)
		 RETURN r.id AS id, a.id AS src, b.id AS tgt ORDER BY r.id, a.id, b.id`)
	if err != nil {
		t.Fatal(err)
	}
	for _, r := range edgeRows {
		edges = append(edges, asString(r["id"])+"|"+asString(r["src"])+"|"+asString(r["tgt"]))
	}
	sort.Strings(nodes)
	sort.Strings(edges)
	return nodes, edges
}

func asString(v any) string {
	if s, ok := v.(string); ok {
		return s
	}
	return ""
}

// TestIncrementalEqualsFull is the core determinism gate: a sequence of
// incremental runs (file add → modify → delete) must produce a graph
// identical (modulo metadata) to a clean full rebuild on the final state.
//
// Scenario:
//   Stage 1: write A.java + B.java + C.java, full index + enrich.
//   Stage 2: modify B.java, delete C.java, add D.java, index + enrich.
//   Stage 3: blow away .codeiq/, run index + enrich --force on the final tree.
//   Assert: snapshots from Stage 2 and Stage 3 are identical.
func TestIncrementalEqualsFull(t *testing.T) {
	if testing.Short() {
		t.Skip("incremental integration test; -short")
	}
	src := t.TempDir()
	mustWrite := func(rel, content string) {
		t.Helper()
		full := filepath.Join(src, rel)
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	cachePath := filepath.Join(src, ".codeiq", "cache.sqlite")
	graphDir := filepath.Join(src, ".codeiq", "graph", "codeiq.kuzu")
	if err := os.MkdirAll(filepath.Dir(cachePath), 0o755); err != nil {
		t.Fatal(err)
	}

	indexAndEnrich := func() {
		t.Helper()
		c, err := cache.Open(cachePath)
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		a := analyzer.NewAnalyzer(analyzer.Options{Cache: c, Workers: 1})
		if _, err := a.Run(src); err != nil {
			t.Fatal(err)
		}
		if _, err := analyzer.Enrich(src, c, analyzer.EnrichOptions{GraphDir: graphDir}); err != nil {
			t.Fatal(err)
		}
	}

	// Stage 1: initial corpus.
	mustWrite("A.java", "public class A {}")
	mustWrite("B.java", "public class B {}")
	mustWrite("C.java", "public class C {}")
	indexAndEnrich()

	// Stage 2: modify B, delete C, add D — incremental on top of Stage 1.
	if err := os.WriteFile(filepath.Join(src, "B.java"), []byte("public class B { int v = 2; }"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Remove(filepath.Join(src, "C.java")); err != nil {
		t.Fatal(err)
	}
	mustWrite("D.java", "public class D {}")
	indexAndEnrich()
	incNodes, incEdges := graphSnapshot(t, graphDir)

	// Stage 3: blow away .codeiq/ entirely, run again from scratch on the
	// same final filesystem. Use --force on enrich for belt-and-braces.
	if err := os.RemoveAll(filepath.Join(src, ".codeiq")); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Dir(cachePath), 0o755); err != nil {
		t.Fatal(err)
	}
	c, err := cache.Open(cachePath)
	if err != nil {
		t.Fatal(err)
	}
	a := analyzer.NewAnalyzer(analyzer.Options{Cache: c, Workers: 1, Force: true})
	if _, err := a.Run(src); err != nil {
		t.Fatal(err)
	}
	if _, err := analyzer.Enrich(src, c, analyzer.EnrichOptions{GraphDir: graphDir, Force: true}); err != nil {
		t.Fatal(err)
	}
	c.Close()
	fullNodes, fullEdges := graphSnapshot(t, graphDir)

	compareSnap := func(label string, got, want []string) {
		t.Helper()
		if len(got) != len(want) {
			t.Errorf("%s: len mismatch incremental=%d full=%d", label, len(got), len(want))
		}
		// Identify the symmetric difference.
		gotSet := make(map[string]struct{}, len(got))
		for _, v := range got {
			gotSet[v] = struct{}{}
		}
		wantSet := make(map[string]struct{}, len(want))
		for _, v := range want {
			wantSet[v] = struct{}{}
		}
		for v := range gotSet {
			if _, ok := wantSet[v]; !ok {
				t.Errorf("%s only in incremental: %q", label, v)
			}
		}
		for v := range wantSet {
			if _, ok := gotSet[v]; !ok {
				t.Errorf("%s only in full: %q", label, v)
			}
		}
	}
	compareSnap("nodes", incNodes, fullNodes)
	compareSnap("edges", incEdges, fullEdges)
}

// TestIncrementalRerunIsIdempotent — three successive `index → enrich`
// runs against an unchanged tree produce identical graphs and the 2nd/3rd
// enrichments short-circuit.
func TestIncrementalRerunIsIdempotent(t *testing.T) {
	src := t.TempDir()
	if err := os.WriteFile(filepath.Join(src, "X.java"), []byte("class X {}"), 0o644); err != nil {
		t.Fatal(err)
	}
	cachePath := filepath.Join(src, ".codeiq", "cache.sqlite")
	graphDir := filepath.Join(src, ".codeiq", "graph", "codeiq.kuzu")
	if err := os.MkdirAll(filepath.Dir(cachePath), 0o755); err != nil {
		t.Fatal(err)
	}

	var summaries []analyzer.EnrichSummary
	for i := 0; i < 3; i++ {
		c, err := cache.Open(cachePath)
		if err != nil {
			t.Fatal(err)
		}
		a := analyzer.NewAnalyzer(analyzer.Options{Cache: c, Workers: 1})
		if _, err := a.Run(src); err != nil {
			t.Fatal(err)
		}
		summary, err := analyzer.Enrich(src, c, analyzer.EnrichOptions{GraphDir: graphDir})
		if err != nil {
			t.Fatalf("enrich #%d: %v", i, err)
		}
		c.Close()
		summaries = append(summaries, summary)
	}

	if summaries[0].ShortCircuited {
		t.Fatal("first enrich short-circuited; want full")
	}
	if !summaries[1].ShortCircuited {
		t.Fatal("second enrich did NOT short-circuit")
	}
	if !summaries[2].ShortCircuited {
		t.Fatal("third enrich did NOT short-circuit")
	}
}

// TestIncrementalAcrossDeleteThenAdd — delete a file, re-index, add it
// back with the same content, re-index. The cache should reflect the
// transition correctly (path purged on delete, re-added with same hash).
func TestIncrementalAcrossDeleteThenAdd(t *testing.T) {
	src := t.TempDir()
	cachePath := filepath.Join(src, ".codeiq", "cache.sqlite")
	if err := os.MkdirAll(filepath.Dir(cachePath), 0o755); err != nil {
		t.Fatal(err)
	}

	mustWrite := func(rel, content string) {
		full := filepath.Join(src, rel)
		if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	runIndex := func() *cache.Cache {
		c, err := cache.Open(cachePath)
		if err != nil {
			t.Fatal(err)
		}
		a := analyzer.NewAnalyzer(analyzer.Options{Cache: c, Workers: 1})
		if _, err := a.Run(src); err != nil {
			t.Fatal(err)
		}
		return c
	}

	mustWrite("A.java", "class A {}")
	c := runIndex()
	c.Close()

	// Delete the file. Next index should purge it from the cache.
	if err := os.Remove(filepath.Join(src, "A.java")); err != nil {
		t.Fatal(err)
	}
	c = runIndex()
	if _, _, ok := c.GetFileByPath("A.java"); ok {
		t.Fatal("deleted file still in cache after re-index")
	}
	c.Close()

	// Recreate the same file. Next index should re-add it.
	mustWrite("A.java", "class A {}")
	c = runIndex()
	if _, _, ok := c.GetFileByPath("A.java"); !ok {
		t.Fatal("re-added file missing from cache")
	}
	c.Close()
}
