package cli

import (
	"bytes"
	"path/filepath"
	"strings"
	"testing"
)

// TestFindSubcommandsRegistered runs each finder against fixture-minimal,
// asserts exit 0 and no panic. The fixture has 1 service / 2 endpoints / 1
// entity (per the index of UserController + User + models.py) so each
// finder produces non-empty output for at least `endpoints` and `entities`.
func TestFindSubcommandsRegistered(t *testing.T) {
	dir := statsFixtureDir(t)
	cases := []struct {
		sub  string
		want []string // labels that should appear; empty = any output OK
	}{
		{"endpoints", nil},
		{"guards", nil},
		{"entities", nil},
		{"topics", nil},
		{"queues", nil},
		{"services", nil},
		{"databases", nil},
		{"components", nil},
	}
	for _, tc := range cases {
		t.Run(tc.sub, func(t *testing.T) {
			root := NewRootCommand()
			root.SetArgs([]string{
				"find", tc.sub,
				"--graph-dir", filepath.Join(dir, "graph.kuzu"),
				dir,
			})
			var out bytes.Buffer
			root.SetOut(&out)
			root.SetErr(&out)
			if err := root.Execute(); err != nil {
				t.Fatalf("find %s: %v\n%s", tc.sub, err, out.String())
			}
		})
	}
}

// TestFindEndpointsReturnsRows asserts that running `find endpoints`
// against fixture-minimal lists the controller endpoints — fixture-minimal
// has 3 GET/POST endpoints on /api/users.
func TestFindEndpointsReturnsRows(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"find", "endpoints",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("find endpoints: %v\n%s", err, out.String())
	}
	// fixture-minimal has 5 endpoints (3 Java + 2 Python). Assert at least
	// 3 tab-separated rows and that one of the controller methods appears
	// in the output.
	rows := strings.Split(strings.TrimSpace(out.String()), "\n")
	if len(rows) < 3 {
		t.Fatalf("find endpoints returned %d rows, want >=3:\n%s", len(rows), out.String())
	}
	if !strings.Contains(out.String(), "createUser") {
		t.Fatalf("find endpoints missing createUser:\n%s", out.String())
	}
}

// TestFindParentHelp asserts that running `codeiq find` without a
// subcommand renders the help text.
func TestFindParentHelp(t *testing.T) {
	root := NewRootCommand()
	root.SetArgs([]string{"find"})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("find parent: %v", err)
	}
	if !strings.Contains(out.String(), "Available Commands") {
		t.Fatalf("find parent did not print help:\n%s", out.String())
	}
}
