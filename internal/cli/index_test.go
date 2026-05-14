package cli

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestIndexRejectsNonDirectory(t *testing.T) {
	cmd := NewRootCommand()
	cmd.SetArgs([]string{"index", "/this/path/does/not/exist"})
	var out, errBuf bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&errBuf)
	err := cmd.Execute()
	if err == nil {
		t.Fatal("expected error on missing path arg")
	}
}

func TestIndexSmokeRun(t *testing.T) {
	dir := t.TempDir()
	_ = os.WriteFile(filepath.Join(dir, "a.java"), []byte("public class A {}"), 0644)

	cmd := NewRootCommand()
	cmd.SetArgs([]string{"index", dir})
	var out bytes.Buffer
	cmd.SetOut(&out)
	cmd.SetErr(&out)
	if err := cmd.Execute(); err != nil {
		t.Fatalf("index errored: %v\n%s", err, out.String())
	}
	if !strings.Contains(out.String(), "Files:") {
		t.Fatalf("expected stats summary in output:\n%s", out.String())
	}
	// Cache file should exist under <path>/.codeiq/cache/codeiq.sqlite.
	wantFile := filepath.Join(dir, ".codeiq", "cache", "codeiq.sqlite")
	if _, err := os.Stat(wantFile); err != nil {
		t.Fatalf("cache file missing: %v", err)
	}
}
