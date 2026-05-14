package cli

import (
	"strings"
	"testing"
)

// TestMCPCommandIsRegistered asserts the `mcp` subcommand is wired into
// the root command and satisfies the docs contract.
func TestMCPCommandIsRegistered(t *testing.T) {
	root := NewRootCommand()
	var found bool
	for _, c := range root.Commands() {
		if c.Name() == "mcp" {
			found = true
			if c.Short == "" || c.Long == "" || c.Example == "" || c.RunE == nil {
				t.Fatalf("mcp subcommand missing docs / RunE")
			}
			// Sanity: the long help mentions the read-only contract and
			// the .mcp.json registration pattern.
			if !strings.Contains(c.Long, "read-only") {
				t.Errorf("mcp Long missing 'read-only' context: %s", c.Long)
			}
			if !strings.Contains(c.Long, ".mcp.json") {
				t.Errorf("mcp Long missing .mcp.json registration example: %s", c.Long)
			}
			break
		}
	}
	if !found {
		t.Fatal("mcp subcommand not registered")
	}
}

// TestMCPCommandHasExpectedFlags asserts the canonical flags (graph-dir,
// max-results, max-depth, query-timeout) are wired onto `mcp`.
func TestMCPCommandHasExpectedFlags(t *testing.T) {
	root := NewRootCommand()
	for _, c := range root.Commands() {
		if c.Name() != "mcp" {
			continue
		}
		for _, name := range []string{"graph-dir", "max-results", "max-depth", "query-timeout"} {
			if c.Flags().Lookup(name) == nil {
				t.Errorf("mcp missing flag --%s", name)
			}
		}
		return
	}
	t.Fatal("mcp subcommand not registered")
}
