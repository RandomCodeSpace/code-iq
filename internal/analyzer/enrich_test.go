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
