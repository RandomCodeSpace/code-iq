package cache

import (
	"path/filepath"
	"testing"

	"github.com/randomcodespace/codeiq/internal/model"
)

func TestGetFileByPathReturnsHashWhenPresent(t *testing.T) {
	c, err := Open(filepath.Join(t.TempDir(), "c.sqlite"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer c.Close()

	e := &Entry{
		ContentHash: "abc123",
		Path:        "foo/bar.go",
		Language:    "go",
		ParsedAt:    "2026-05-15T00:00:00Z",
		Nodes:       []*model.CodeNode{model.NewCodeNode("n1", model.NodeClass, "Bar")},
	}
	if err := c.Put(e); err != nil {
		t.Fatalf("put: %v", err)
	}

	hash, parsedAt, ok := c.GetFileByPath("foo/bar.go")
	if !ok {
		t.Fatal("ok = false, want true")
	}
	if hash != "abc123" {
		t.Fatalf("hash = %q, want %q", hash, "abc123")
	}
	if parsedAt != "2026-05-15T00:00:00Z" {
		t.Fatalf("parsedAt = %q, want %q", parsedAt, "2026-05-15T00:00:00Z")
	}
}

func TestGetFileByPathReturnsFalseWhenAbsent(t *testing.T) {
	c, err := Open(filepath.Join(t.TempDir(), "c.sqlite"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer c.Close()
	if _, _, ok := c.GetFileByPath("does/not/exist"); ok {
		t.Fatal("ok = true, want false for missing path")
	}
}
