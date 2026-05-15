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

func TestAllFilesYieldsEveryRow(t *testing.T) {
	c, err := Open(filepath.Join(t.TempDir(), "c.sqlite"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer c.Close()
	for _, e := range []*Entry{
		{ContentHash: "h1", Path: "a.go", Language: "go", ParsedAt: "t"},
		{ContentHash: "h2", Path: "b.go", Language: "go", ParsedAt: "t"},
		{ContentHash: "h3", Path: "c.go", Language: "go", ParsedAt: "t"},
	} {
		if err := c.Put(e); err != nil {
			t.Fatalf("put: %v", err)
		}
	}
	got := map[string]string{}
	if err := c.AllFiles(func(path, hash string) error {
		got[path] = hash
		return nil
	}); err != nil {
		t.Fatalf("AllFiles: %v", err)
	}
	want := map[string]string{"a.go": "h1", "b.go": "h2", "c.go": "h3"}
	if len(got) != len(want) {
		t.Fatalf("got %d entries, want %d: %v", len(got), len(want), got)
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("got[%q]=%q, want %q", k, got[k], v)
		}
	}
}

func TestPurgeByPathRemovesAllRows(t *testing.T) {
	c, err := Open(filepath.Join(t.TempDir(), "c.sqlite"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer c.Close()
	e := &Entry{
		ContentHash: "abc",
		Path:        "foo.go",
		Language:    "go",
		ParsedAt:    "t",
		Nodes:       []*model.CodeNode{model.NewCodeNode("n1", model.NodeClass, "Foo")},
		Edges:       []*model.CodeEdge{model.NewCodeEdge("e1", model.EdgeContains, "n1", "n2")},
	}
	if err := c.Put(e); err != nil {
		t.Fatalf("put: %v", err)
	}
	if err := c.PurgeByPath("foo.go"); err != nil {
		t.Fatalf("purge: %v", err)
	}
	if _, _, ok := c.GetFileByPath("foo.go"); ok {
		t.Fatal("file row still present after purge")
	}
	if c.Has("abc") {
		t.Fatal("cache.Has returns true after purge")
	}
}

func TestPurgeByPathIsNoOpForMissingPath(t *testing.T) {
	c, err := Open(filepath.Join(t.TempDir(), "c.sqlite"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer c.Close()
	if err := c.PurgeByPath("nope.go"); err != nil {
		t.Fatalf("purge of missing path should be no-op, got: %v", err)
	}
}

func TestPurgeByPathLeavesUnrelatedRows(t *testing.T) {
	c, err := Open(filepath.Join(t.TempDir(), "c.sqlite"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer c.Close()
	_ = c.Put(&Entry{
		ContentHash: "h-keep",
		Path:        "keep.go",
		Language:    "go",
		ParsedAt:    "t",
		Nodes:       []*model.CodeNode{model.NewCodeNode("k", model.NodeClass, "K")},
	})
	_ = c.Put(&Entry{
		ContentHash: "h-drop",
		Path:        "drop.go",
		Language:    "go",
		ParsedAt:    "t",
		Nodes:       []*model.CodeNode{model.NewCodeNode("d", model.NodeClass, "D")},
	})
	if err := c.PurgeByPath("drop.go"); err != nil {
		t.Fatal(err)
	}
	if !c.Has("h-keep") {
		t.Fatal("unrelated file purged")
	}
	if c.Has("h-drop") {
		t.Fatal("target hash still present")
	}
}

func TestAllFilesIteratesInPathOrder(t *testing.T) {
	c, err := Open(filepath.Join(t.TempDir(), "c.sqlite"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer c.Close()
	// Insert in non-alphabetical order on purpose.
	for _, e := range []*Entry{
		{ContentHash: "h-c", Path: "c.go", Language: "go", ParsedAt: "t"},
		{ContentHash: "h-a", Path: "a.go", Language: "go", ParsedAt: "t"},
		{ContentHash: "h-b", Path: "b.go", Language: "go", ParsedAt: "t"},
	} {
		if err := c.Put(e); err != nil {
			t.Fatalf("put: %v", err)
		}
	}
	var paths []string
	_ = c.AllFiles(func(path, _ string) error {
		paths = append(paths, path)
		return nil
	})
	want := []string{"a.go", "b.go", "c.go"}
	if len(paths) != len(want) {
		t.Fatalf("len(paths)=%d, want %d", len(paths), len(want))
	}
	for i := range paths {
		if paths[i] != want[i] {
			t.Errorf("paths[%d]=%q, want %q", i, paths[i], want[i])
		}
	}
}
