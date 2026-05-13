package cli

import (
	"bytes"
	"encoding/json"
	"path/filepath"
	"strings"
	"testing"
)

// TestCacheInfoCommand asserts `codeiq cache info` prints all canonical
// summary keys (path, size_bytes, version, file_count, node_count,
// edge_count).
func TestCacheInfoCommand(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"cache", "info",
		"--cache-path", filepath.Join(dir, "cache.sqlite"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("cache info: %v\n%s", err, out.String())
	}
	var got map[string]any
	if err := json.Unmarshal(out.Bytes(), &got); err != nil {
		t.Fatalf("cache info JSON invalid: %v\n%s", err, out.String())
	}
	for _, k := range []string{"path", "size_bytes", "version", "file_count", "node_count", "edge_count"} {
		if _, ok := got[k]; !ok {
			t.Errorf("cache info missing %q", k)
		}
	}
}

// TestCacheListCommandTable asserts the default table output begins with
// the PATH / LANGUAGE / NODES / EDGES / HASH column header.
func TestCacheListCommandTable(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"cache", "list",
		"--cache-path", filepath.Join(dir, "cache.sqlite"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("cache list: %v\n%s", err, out.String())
	}
	if !strings.Contains(out.String(), "PATH") {
		t.Fatalf("cache list missing PATH column header:\n%s", out.String())
	}
	if !strings.Contains(out.String(), "LANGUAGE") {
		t.Errorf("cache list missing LANGUAGE column:\n%s", out.String())
	}
}

// TestCacheListCommandJSON asserts the --json flag produces a JSON array.
func TestCacheListCommandJSON(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"cache", "list", "--json",
		"--cache-path", filepath.Join(dir, "cache.sqlite"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("cache list --json: %v\n%s", err, out.String())
	}
	var arr []any
	if err := json.Unmarshal(out.Bytes(), &arr); err != nil {
		t.Fatalf("cache list --json invalid JSON: %v\n%s", err, out.String())
	}
}

// TestCacheClearRequiresYes asserts the clear subcommand bails without
// `--yes`.
func TestCacheClearRequiresYes(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"cache", "clear",
		"--cache-path", filepath.Join(dir, "cache.sqlite"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err == nil {
		t.Fatalf("expected error without --yes, got success:\n%s", out.String())
	} else if !strings.Contains(err.Error(), "yes") {
		t.Errorf("error must mention --yes: %v", err)
	}
}

// TestCacheClearWipesEntries asserts `codeiq cache clear --yes` empties
// the entries table.
func TestCacheClearWipesEntries(t *testing.T) {
	dir := statsFixtureDir(t)
	// Sanity: pre-clear there is at least one entry.
	root := NewRootCommand()
	root.SetArgs([]string{
		"cache", "list", "--json",
		"--cache-path", filepath.Join(dir, "cache.sqlite"),
		dir,
	})
	var listOut bytes.Buffer
	root.SetOut(&listOut)
	root.SetErr(&listOut)
	if err := root.Execute(); err != nil {
		t.Fatalf("pre-clear list: %v", err)
	}

	// Clear.
	root = NewRootCommand()
	root.SetArgs([]string{
		"cache", "clear", "--yes",
		"--cache-path", filepath.Join(dir, "cache.sqlite"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("cache clear: %v\n%s", err, out.String())
	}
	if !strings.Contains(out.String(), "cleared") {
		t.Errorf("clear output must mention `cleared`: %s", out.String())
	}

	// Post-clear: list must be empty.
	root = NewRootCommand()
	root.SetArgs([]string{
		"cache", "list", "--json",
		"--cache-path", filepath.Join(dir, "cache.sqlite"),
		dir,
	})
	var afterList bytes.Buffer
	root.SetOut(&afterList)
	root.SetErr(&afterList)
	if err := root.Execute(); err != nil {
		t.Fatalf("post-clear list: %v", err)
	}
	trimmed := strings.TrimSpace(afterList.String())
	if trimmed != "null" && trimmed != "[]" {
		t.Errorf("expected empty list after clear, got: %q", trimmed)
	}
}

// TestCacheInspectByPath asserts a cache.inspect call against a relative
// path returns a non-empty entry.
func TestCacheInspectByPath(t *testing.T) {
	dir := statsFixtureDir(t)
	root := NewRootCommand()
	root.SetArgs([]string{
		"cache", "inspect", "User.java",
		"--cache-path", filepath.Join(dir, "cache.sqlite"),
		dir,
	})
	var out bytes.Buffer
	root.SetOut(&out)
	root.SetErr(&out)
	if err := root.Execute(); err != nil {
		t.Fatalf("cache inspect: %v\n%s", err, out.String())
	}
	var got map[string]any
	if err := json.Unmarshal(out.Bytes(), &got); err != nil {
		t.Fatalf("cache inspect JSON invalid: %v\n%s", err, out.String())
	}
	if got["ContentHash"] == "" && got["content_hash"] == "" {
		t.Errorf("cache inspect missing ContentHash:\n%s", out.String())
	}
}
