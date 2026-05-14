package cli

import (
	"bytes"
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
)

// TestTopologyBareReturnsJSON asserts that running `codeiq topology` against
// fixture-minimal produces a JSON object with services / connections.
func TestTopologyBareReturnsJSON(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"topology",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("topology: %v\n%s", err, out.String())
	}
	var got map[string]any
	if err := json.Unmarshal(out.Bytes(), &got); err != nil {
		t.Fatalf("topology output is not valid JSON: %v\n%s", err, out.String())
	}
	for _, k := range []string{"services", "connections", "service_count", "connection_count"} {
		if _, ok := got[k]; !ok {
			t.Errorf("topology JSON missing %q\n%s", k, out.String())
		}
	}
}

// TestTopologyServiceDetail asserts that `topology service-detail <name>`
// returns a detail object for the named service. fixture-minimal produces
// one SERVICE node named after the temp dir; we resolve the name from the
// bare topology call.
func TestTopologyServiceDetail(t *testing.T) {
	dir := statsFixtureDir(t)
	// Fetch the service name from the bare topology call.
	bare := NewRootCommand()
	bare.SetArgs([]string{
		"topology",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var bareOut bytes.Buffer
	bare.SetOut(&bareOut)
	bare.SetErr(&bareOut)
	if err := bare.Execute(); err != nil {
		t.Fatalf("topology bare: %v\n%s", err, bareOut.String())
	}
	var got struct {
		Services []map[string]any `json:"services"`
	}
	if err := json.Unmarshal(bareOut.Bytes(), &got); err != nil {
		t.Fatalf("decode bare: %v\n%s", err, bareOut.String())
	}
	if len(got.Services) == 0 {
		t.Fatalf("no services in topology:\n%s", bareOut.String())
	}
	svcName, _ := got.Services[0]["name"].(string)
	if svcName == "" {
		t.Fatalf("service name missing from %v", got.Services[0])
	}

	root := NewRootCommand()
	root.SetArgs([]string{
		"topology", "service-detail", svcName,
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("service-detail: %v\n%s", err, out.String())
	}
	var detail map[string]any
	if err := json.Unmarshal(out.Bytes(), &detail); err != nil {
		t.Fatalf("decode service-detail: %v\n%s", err, out.String())
	}
	if detail["name"] != svcName {
		t.Fatalf("service-detail name=%v, want %s", detail["name"], svcName)
	}
}

// TestTopologyBlastRadius asserts that `topology blast-radius <id>` returns
// reachable nodes. Use a SERVICE id from the fixture; the SERVICE has
// CONTAINS edges to every node so depth=2 should reach plenty.
func TestTopologyBlastRadius(t *testing.T) {
	dir := statsFixtureDir(t)
	// Look up a service id via `find services`.
	finder := NewRootCommand()
	finder.SetArgs([]string{
		"find", "services",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var finderOut bytes.Buffer
	finder.SetOut(&finderOut)
	finder.SetErr(&finderOut)
	if err := finder.Execute(); err != nil {
		t.Fatalf("find services: %v\n%s", err, finderOut.String())
	}
	line := strings.SplitN(strings.TrimSpace(finderOut.String()), "\n", 2)[0]
	id := strings.SplitN(line, "\t", 2)[0]
	if id == "" {
		t.Fatalf("no service id in find output: %q", finderOut.String())
	}

	root := NewRootCommand()
	root.SetArgs([]string{
		"topology", "blast-radius", id,
		"--depth", "2",
		"--graph-dir", filepath.Join(dir, "graph.kuzu"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("blast-radius: %v\n%s", err, out.String())
	}
	var br map[string]any
	if err := json.Unmarshal(out.Bytes(), &br); err != nil {
		t.Fatalf("decode blast-radius: %v\n%s", err, out.String())
	}
	if br["source"] != id {
		t.Fatalf("blast-radius source=%v, want %s", br["source"], id)
	}
	if br["affected_node_count"] == nil {
		t.Fatalf("blast-radius missing affected_node_count:\n%s", out.String())
	}
}

// TestTopologyParentHelp asserts the bare topology renders without help
// fallback when service map JSON is the expected output. With no
// subcommand and no --help flag, the parent prints the bare topology
// (the parent IS the bare command, not a help router).
func TestTopologyParentHelp(t *testing.T) {
	root := NewRootCommand()
	root.SetArgs([]string{"topology", "--help"})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("topology --help: %v", err)
	}
	if !strings.Contains(out.String(), "service-detail") {
		t.Fatalf("topology --help missing service-detail subcommand:\n%s", out.String())
	}
}
