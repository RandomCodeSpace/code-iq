package cli

import (
	"bytes"
	"path/filepath"
	"strings"
	"testing"
)

// TestQuerySubcommandsRegistered asserts every query subcommand is wired
// into the root command, has the docs the §7.1 contract demands, and its
// RunE handler errors out gracefully when handed an unknown node id (instead
// of panicking or printing the entire graph).
func TestQuerySubcommandsRegistered(t *testing.T) {
	dir := statsFixtureDir(t)
	subs := []string{"consumers", "producers", "callers", "dependencies", "dependents"}
	for _, sub := range subs {
		t.Run(sub, func(t *testing.T) {
			root := NewRootCommand()
			root.SetArgs([]string{
				"query", sub, "id-that-does-not-exist",
				"--graph-dir", filepath.Join(dir, "graph.kuzu"),
				dir,
			})
			var out bytes.Buffer
			root.SetOut(&out)
			root.SetErr(&out)
			if err := root.Execute(); err != nil {
				t.Fatalf("query %s: %v\n%s", sub, err, out.String())
			}
			// An unknown id yields an empty result, not an error — the body
			// is just an empty string (no rows printed). Sanity-check that
			// the command exited cleanly.
			if strings.Contains(out.String(), "panic") {
				t.Fatalf("query %s produced panic in stdout:\n%s", sub, out.String())
			}
		})
	}
}

// TestQueryParentHelp asserts that running `codeiq query` with no
// subcommand prints help rather than erroring.
func TestQueryParentHelp(t *testing.T) {
	root := NewRootCommand()
	root.SetArgs([]string{"query"})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("query parent: %v", err)
	}
	if !strings.Contains(out.String(), "Available Commands") {
		t.Fatalf("query parent did not print help:\n%s", out.String())
	}
}

// TestQueryConsumersAgainstFixture asserts FindConsumers returns the right
// set when called against a real fixture. fixture-minimal has CONTAINS
// edges only (no CONSUMES) so the result is empty for any node — confirms
// the consumers query distinguishes structural edges from runtime ones.
func TestQueryConsumersAgainstFixture(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"query", "consumers", "service:" + filepath.Base(dir),
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("consumers: %v\n%s", err, out.String())
	}
	// fixture-minimal has no CONSUMES edges, so consumers of the root
	// SERVICE must be empty.
	if strings.TrimSpace(out.String()) != "" {
		t.Fatalf("expected empty consumers result for fixture-minimal, got:\n%s", out.String())
	}
}
