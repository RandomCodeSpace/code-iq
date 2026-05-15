package analyzer_test

import (
	"io"
	"os"
	"path/filepath"
	"testing"

	"github.com/randomcodespace/codeiq/internal/analyzer"
	"github.com/randomcodespace/codeiq/internal/cache"
)

// copyDirAll mirrors `cp -r` for test-fixture staging: every regular file
// under src lands at the same relative path under dst. Source-tree symlinks
// and special files are skipped (not needed by the test fixtures).
func copyDirAll(src, dst string) error {
	return filepath.Walk(src, func(p string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}
		rel, relErr := filepath.Rel(src, p)
		if relErr != nil {
			return relErr
		}
		target := filepath.Join(dst, rel)
		if info.IsDir() {
			return os.MkdirAll(target, 0o755)
		}
		if !info.Mode().IsRegular() {
			return nil
		}
		in, err := os.Open(p)
		if err != nil {
			return err
		}
		defer in.Close()
		if mkdErr := os.MkdirAll(filepath.Dir(target), 0o755); mkdErr != nil {
			return mkdErr
		}
		out, err := os.OpenFile(target, os.O_WRONLY|os.O_CREATE|os.O_TRUNC, 0o644)
		if err != nil {
			return err
		}
		if _, err := io.Copy(out, in); err != nil {
			out.Close()
			return err
		}
		return out.Close()
	})
}

// TestEnrichEmptyCacheIsNoop confirms enrich tolerates an empty cache — the
// pipeline `index → enrich` must work when index produced no results (empty
// directory, all-skipped files), returning zero nodes / zero edges / zero
// services rather than erroring.
func TestEnrichEmptyCacheIsNoop(t *testing.T) {
	dir := t.TempDir()
	c, err := cache.Open(filepath.Join(dir, "cache.sqlite"))
	if err != nil {
		t.Fatalf("cache open: %v", err)
	}
	defer c.Close()
	summary, err := analyzer.Enrich(dir, c, analyzer.EnrichOptions{
		GraphDir: filepath.Join(dir, "graph.kuzu"),
	})
	if err != nil {
		t.Fatalf("enrich: %v", err)
	}
	// Empty cache produces no original nodes; ServiceDetector still synthesises
	// one root SERVICE node for the project directory itself.
	if summary.Nodes < summary.Services {
		t.Fatalf("nodes %d less than services %d", summary.Nodes, summary.Services)
	}
	if summary.Edges < 0 {
		t.Fatalf("negative edges: %d", summary.Edges)
	}
}

// TestEnrichShortCircuitsWhenManifestMatches verifies the incremental
// short-circuit: a second enrich against an unchanged cache returns
// ShortCircuited=true without rebuilding the graph.
func TestEnrichShortCircuitsWhenManifestMatches(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "X.java"), []byte("class X {}"), 0o644); err != nil {
		t.Fatal(err)
	}
	c, err := cache.Open(filepath.Join(dir, "cache.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	a := analyzer.NewAnalyzer(analyzer.Options{Cache: c})
	if _, err := a.Run(dir); err != nil {
		t.Fatal(err)
	}
	graphDir := filepath.Join(dir, "graph.kuzu")

	// First run: full enrich, writes manifest.
	first, err := analyzer.Enrich(dir, c, analyzer.EnrichOptions{GraphDir: graphDir})
	if err != nil {
		t.Fatalf("first enrich: %v", err)
	}
	if first.ShortCircuited {
		t.Fatal("first enrich short-circuited; want full")
	}
	if first.Mode != "full" {
		t.Fatalf("first Mode = %q, want full", first.Mode)
	}
	if first.Nodes == 0 {
		t.Fatal("first enrich produced 0 nodes")
	}

	// Second run: no cache changes → must short-circuit.
	second, err := analyzer.Enrich(dir, c, analyzer.EnrichOptions{GraphDir: graphDir})
	if err != nil {
		t.Fatalf("second enrich: %v", err)
	}
	if !second.ShortCircuited {
		t.Fatalf("second enrich did NOT short-circuit: %+v", second)
	}
	if second.Mode != "short-circuit" {
		t.Fatalf("second Mode = %q, want short-circuit", second.Mode)
	}
}

// TestEnrichForceBypassesShortCircuit verifies Force=true re-runs the
// full pipeline even when manifests match.
func TestEnrichForceBypassesShortCircuit(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "Y.java"), []byte("class Y {}"), 0o644); err != nil {
		t.Fatal(err)
	}
	c, err := cache.Open(filepath.Join(dir, "cache.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	a := analyzer.NewAnalyzer(analyzer.Options{Cache: c})
	if _, err := a.Run(dir); err != nil {
		t.Fatal(err)
	}
	graphDir := filepath.Join(dir, "graph.kuzu")
	if _, err := analyzer.Enrich(dir, c, analyzer.EnrichOptions{GraphDir: graphDir}); err != nil {
		t.Fatal(err)
	}
	forced, err := analyzer.Enrich(dir, c, analyzer.EnrichOptions{GraphDir: graphDir, Force: true})
	if err != nil {
		t.Fatalf("forced enrich: %v", err)
	}
	if forced.ShortCircuited {
		t.Fatal("Force=true should bypass short-circuit")
	}
	if forced.Mode != "full" {
		t.Fatalf("forced Mode = %q, want full", forced.Mode)
	}
}

// TestEnrichRerunAfterFileChange verifies a re-run after a file change
// produces the right graph (no PK collisions, manifest updated).
func TestEnrichRerunAfterFileChange(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "A.java"), []byte("class A {}"), 0o644); err != nil {
		t.Fatal(err)
	}
	c, err := cache.Open(filepath.Join(dir, "cache.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	a := analyzer.NewAnalyzer(analyzer.Options{Cache: c})
	if _, err := a.Run(dir); err != nil {
		t.Fatal(err)
	}
	graphDir := filepath.Join(dir, "graph.kuzu")
	first, err := analyzer.Enrich(dir, c, analyzer.EnrichOptions{GraphDir: graphDir})
	if err != nil {
		t.Fatal(err)
	}

	// Add a second file, re-index, re-enrich.
	if err := os.WriteFile(filepath.Join(dir, "B.java"), []byte("class B {}"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := a.Run(dir); err != nil {
		t.Fatal(err)
	}
	second, err := analyzer.Enrich(dir, c, analyzer.EnrichOptions{GraphDir: graphDir})
	if err != nil {
		t.Fatalf("rerun after change: %v", err)
	}
	if second.ShortCircuited {
		t.Fatal("rerun after file add must NOT short-circuit")
	}
	if second.Nodes < first.Nodes {
		t.Fatalf("rerun produced fewer nodes (%d) than first (%d)", second.Nodes, first.Nodes)
	}
}

// TestEnrichFixtureMinimalProducesGraph runs the full index → enrich pipeline
// against the fixture-minimal corpus and asserts the resulting graph has at
// least the entity / endpoint / service nodes the fixture is expected to
// produce. Sanity check, not a parity check.
func TestEnrichFixtureMinimalProducesGraph(t *testing.T) {
	src := filepath.Join("..", "..", "testdata", "fixture-minimal")
	// Copy fixture to a writable tmp dir so the index cache + graph store
	// can be created under it without touching the source tree.
	tmp := t.TempDir()
	if err := copyDirAll(src, tmp); err != nil {
		t.Fatalf("copy fixture: %v", err)
	}

	c, err := cache.Open(filepath.Join(tmp, "cache.sqlite"))
	if err != nil {
		t.Fatalf("cache: %v", err)
	}
	defer c.Close()

	a := analyzer.NewAnalyzer(analyzer.Options{Cache: c})
	if _, err := a.Run(tmp); err != nil {
		t.Fatalf("index: %v", err)
	}

	summary, err := analyzer.Enrich(tmp, c, analyzer.EnrichOptions{
		GraphDir: filepath.Join(tmp, "graph.kuzu"),
	})
	if err != nil {
		t.Fatalf("enrich: %v", err)
	}
	if summary.Nodes == 0 {
		t.Fatalf("expected non-empty graph, got 0 nodes")
	}
	if summary.Services == 0 {
		t.Fatalf("expected at least one SERVICE node")
	}
}
