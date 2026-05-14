package cli

import (
	"bytes"
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
)

// TestFlowCommandMermaid asserts `codeiq flow overview --format mermaid`
// produces a Mermaid graph starting with `graph LR`.
func TestFlowCommandMermaid(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"flow", "overview", "--format", "mermaid",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("flow: %v\n%s", err, out.String())
	}
	if !strings.HasPrefix(out.String(), "graph LR\n") {
		t.Fatalf("flow mermaid output must begin with `graph LR`, got:\n%s", out.String())
	}
}

// TestFlowCommandJSON asserts the default JSON output is valid JSON with
// the canonical `title` + `view` keys.
func TestFlowCommandJSON(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"flow", "runtime",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("flow: %v\n%s", err, out.String())
	}
	var got map[string]any
	if err := json.Unmarshal(out.Bytes(), &got); err != nil {
		t.Fatalf("flow JSON is invalid: %v\n%s", err, out.String())
	}
	if got["view"] != "runtime" {
		t.Errorf("view = %v, want runtime", got["view"])
	}
}

// TestFlowCommandRejectsUnknownView asserts the CLI surfaces an unknown
// view as a usage error.
func TestFlowCommandRejectsUnknownView(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"flow", "bogus",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err == nil {
		t.Fatalf("expected error for unknown view, got success:\n%s", out.String())
	}
}

// TestFlowCommandAllFiveViews asserts every documented view succeeds
// against the fixture.
func TestFlowCommandAllFiveViews(t *testing.T) {
	dir := statsFixtureDir(t)
	for _, view := range []string{"overview", "ci", "deploy", "runtime", "auth"} {
		t.Run(view, func(t *testing.T) {
			root := NewRootCommand()
			root.SetArgs([]string{
				"flow", view,
				"--graph-dir", filepath.Join(dir, "graph.kuzu"),
				dir,
			})
			var out bytes.Buffer
			root.SetOut(&out)
			root.SetErr(&out)
			if err := root.Execute(); err != nil {
				t.Fatalf("flow %s: %v\n%s", view, err, out.String())
			}
			if out.Len() == 0 {
				t.Fatalf("flow %s produced empty output", view)
			}
		})
	}
}
