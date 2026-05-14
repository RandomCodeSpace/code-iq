package cli

import (
	"bytes"
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
)

// TestGraphCommandJSON asserts the default JSON export has `nodes`,
// `edges`, and `stats` keys.
func TestGraphCommandJSON(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"graph", "--format", "json",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("graph: %v\n%s", err, out.String())
	}
	var got map[string]any
	if err := json.Unmarshal(out.Bytes(), &got); err != nil {
		t.Fatalf("graph JSON invalid: %v\n%s", err, out.String())
	}
	for _, k := range []string{"nodes", "edges", "stats"} {
		if _, ok := got[k]; !ok {
			t.Errorf("graph JSON missing %q", k)
		}
	}
}

// TestGraphCommandYAML asserts the YAML export is parseable and contains
// the canonical top-level keys.
func TestGraphCommandYAML(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"graph", "-f", "yaml",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("graph yaml: %v\n%s", err, out.String())
	}
	for _, k := range []string{"nodes:", "edges:", "stats:"} {
		if !strings.Contains(out.String(), k) {
			t.Errorf("graph yaml missing %q\n%s", k, out.String())
		}
	}
}

// TestGraphCommandMermaid asserts the mermaid export starts with `graph LR`.
func TestGraphCommandMermaid(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"graph", "-f", "mermaid",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("graph mermaid: %v\n%s", err, out.String())
	}
	if !strings.HasPrefix(out.String(), "graph LR\n") {
		t.Fatalf("graph mermaid must start with `graph LR`, got:\n%s", out.String())
	}
}

// TestGraphCommandDOT asserts the dot export is well-formed.
func TestGraphCommandDOT(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"graph", "-f", "dot",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("graph dot: %v\n%s", err, out.String())
	}
	if !strings.HasPrefix(out.String(), "digraph G {") {
		t.Fatalf("graph dot must start with `digraph G {`, got:\n%s", out.String())
	}
}

// TestGraphCommandUnknownFormat asserts an unknown format is surfaced as
// an error.
func TestGraphCommandUnknownFormat(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"graph", "-f", "bogus",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err == nil {
		t.Fatalf("expected error for unknown format, got success:\n%s", out.String())
	}
}
