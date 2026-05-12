package cli

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/analyzer"
	"github.com/randomcodespace/codeiq/go/internal/cache"
)

// statsFixtureDir copies the fixture-minimal corpus into a fresh temp dir,
// runs index + enrich, and returns the absolute path. The returned graph is
// the same shape exercised by every stats subtest — keeps test setup linear.
func statsFixtureDir(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	src := filepath.Join("..", "..", "testdata", "fixture-minimal")
	entries, err := os.ReadDir(src)
	if err != nil {
		t.Fatalf("read fixture: %v", err)
	}
	for _, ent := range entries {
		if ent.IsDir() {
			continue
		}
		data, err := os.ReadFile(filepath.Join(src, ent.Name()))
		if err != nil {
			t.Fatalf("read %s: %v", ent.Name(), err)
		}
		if err := os.WriteFile(filepath.Join(dir, ent.Name()), data, 0o644); err != nil {
			t.Fatalf("write %s: %v", ent.Name(), err)
		}
	}
	c, err := cache.Open(filepath.Join(dir, "cache.sqlite"))
	if err != nil {
		t.Fatalf("cache open: %v", err)
	}
	defer c.Close()
	a := analyzer.NewAnalyzer(analyzer.Options{Cache: c})
	if _, err := a.Run(dir); err != nil {
		t.Fatalf("index: %v", err)
	}
	if _, err := analyzer.Enrich(dir, c, analyzer.EnrichOptions{
		GraphDir: filepath.Join(dir, "graph.kuzu"),
	}); err != nil {
		t.Fatalf("enrich: %v", err)
	}
	return dir
}

// TestStatsCommandJSON asserts the stats command emits a JSON object with
// the seven canonical categories when --json is set.
func TestStatsCommandJSON(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"stats", "--json",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("stats: %v\n%s", err, out.String())
	}
	var got map[string]any
	if err := json.Unmarshal(out.Bytes(), &got); err != nil {
		t.Fatalf("stats output is not valid JSON: %v\n%s", err, out.String())
	}
	for _, k := range []string{
		"graph", "languages", "frameworks", "infra",
		"connections", "auth", "architecture",
	} {
		if _, ok := got[k]; !ok {
			t.Errorf("stats JSON missing category %q\nfull output:\n%s", k, out.String())
		}
	}
}

// TestStatsCommandCategory asserts --category restricts the output to a
// single category and that the JSON is non-empty for `graph`.
func TestStatsCommandCategory(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"stats", "--json", "--category", "graph",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("stats: %v\n%s", err, out.String())
	}
	var got map[string]any
	if err := json.Unmarshal(out.Bytes(), &got); err != nil {
		t.Fatalf("stats output is not valid JSON: %v\n%s", err, out.String())
	}
	if _, ok := got["nodes"]; !ok {
		t.Errorf("category=graph response missing `nodes` key:\n%s", out.String())
	}
}

// TestStatsCommandDefaultRendering asserts the default (non-JSON) rendering
// emits at least the "nodes" key — we use JSON for human view too because
// it's deterministic and trivial to grep.
func TestStatsCommandDefaultRendering(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"stats",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("stats: %v\n%s", err, out.String())
	}
	if !strings.Contains(out.String(), "nodes") {
		t.Fatalf("stats default render missing nodes counter:\n%s", out.String())
	}
}
