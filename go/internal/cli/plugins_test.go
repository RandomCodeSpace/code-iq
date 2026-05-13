package cli

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
)

// TestPluginsListTable asserts `codeiq plugins list` prints a table with
// at least one detector row.
func TestPluginsListTable(t *testing.T) {
	root := NewRootCommand()
	root.SetArgs([]string{"plugins", "list"})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("plugins list: %v\n%s", err, out.String())
	}
	if !strings.Contains(out.String(), "NAME") {
		t.Fatalf("plugins list missing NAME column header:\n%s", out.String())
	}
	if !strings.Contains(out.String(), "CATEGORY") {
		t.Errorf("plugins list missing CATEGORY column header:\n%s", out.String())
	}
	// Phase 1 ships spring_rest; check it's present.
	if !strings.Contains(out.String(), "spring_rest") {
		t.Errorf("plugins list missing spring_rest row:\n%s", out.String())
	}
}

// TestPluginsListJSON asserts the --json flag produces a JSON array
// containing detector names and categories.
func TestPluginsListJSON(t *testing.T) {
	root := NewRootCommand()
	root.SetArgs([]string{"plugins", "list", "--json"})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("plugins list --json: %v\n%s", err, out.String())
	}
	var arr []map[string]any
	if err := json.Unmarshal(out.Bytes(), &arr); err != nil {
		t.Fatalf("plugins list --json invalid JSON: %v\n%s", err, out.String())
	}
	if len(arr) == 0 {
		t.Fatal("expected at least one detector in --json output")
	}
	for _, k := range []string{"name", "category", "languages", "default_confidence"} {
		if _, ok := arr[0][k]; !ok {
			t.Errorf("first detector missing %q: %v", k, arr[0])
		}
	}
}

// TestPluginsListLanguageFilter asserts --language restricts the list.
func TestPluginsListLanguageFilter(t *testing.T) {
	root := NewRootCommand()
	root.SetArgs([]string{"plugins", "list", "--language", "python", "--json"})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("plugins list --language python: %v\n%s", err, out.String())
	}
	var arr []map[string]any
	if err := json.Unmarshal(out.Bytes(), &arr); err != nil {
		t.Fatalf("invalid JSON: %v", err)
	}
	if len(arr) == 0 {
		t.Fatal("expected at least one python detector")
	}
	for _, r := range arr {
		langs, _ := r["languages"].([]any)
		found := false
		for _, l := range langs {
			if l == "python" {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("detector %v has no python in languages", r["name"])
		}
	}
}

// TestPluginsInspect asserts `codeiq plugins inspect <name>` prints the
// canonical key/value block.
func TestPluginsInspect(t *testing.T) {
	root := NewRootCommand()
	root.SetArgs([]string{"plugins", "inspect", "spring_rest"})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("plugins inspect: %v\n%s", err, out.String())
	}
	for _, k := range []string{"name:", "category:", "languages:", "default_confidence:", "go_type:"} {
		if !strings.Contains(out.String(), k) {
			t.Errorf("plugins inspect missing %q\n%s", k, out.String())
		}
	}
	if !strings.Contains(out.String(), "spring_rest") {
		t.Errorf("plugins inspect did not name detector:\n%s", out.String())
	}
}

// TestPluginsInspectUnknown asserts unknown detector surfaces an error.
func TestPluginsInspectUnknown(t *testing.T) {
	root := NewRootCommand()
	root.SetArgs([]string{"plugins", "inspect", "bogus_does_not_exist"})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err == nil {
		t.Fatalf("expected error for unknown detector, got:\n%s", out.String())
	}
}

// TestCategoryFromPkgPath unit-tests the package-path -> category mapping.
func TestCategoryFromPkgPath(t *testing.T) {
	cases := []struct{ pkgPath, want string }{
		{"github.com/randomcodespace/codeiq/go/internal/detector/jvm/java", "jvm/java"},
		{"github.com/randomcodespace/codeiq/go/internal/detector/python", "python"},
		{"github.com/randomcodespace/codeiq/go/internal/detector/generic", "generic"},
		{"github.com/example/other/package", "unknown"},
	}
	for _, c := range cases {
		if got := categoryFromPkgPath(c.pkgPath); got != c.want {
			t.Errorf("categoryFromPkgPath(%q) = %q, want %q", c.pkgPath, got, c.want)
		}
	}
}
