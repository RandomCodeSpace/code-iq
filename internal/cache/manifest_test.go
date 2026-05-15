package cache

import (
	"path/filepath"
	"testing"
)

func TestManifestHashIsDeterministic(t *testing.T) {
	c, err := Open(filepath.Join(t.TempDir(), "c.sqlite"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer c.Close()
	for _, e := range []*Entry{
		{ContentHash: "h1", Path: "a.go", Language: "go", ParsedAt: "t"},
		{ContentHash: "h2", Path: "b.go", Language: "go", ParsedAt: "t"},
	} {
		if err := c.Put(e); err != nil {
			t.Fatalf("put: %v", err)
		}
	}
	a, err := c.ManifestHash()
	if err != nil {
		t.Fatalf("manifest: %v", err)
	}
	b, _ := c.ManifestHash()
	if a != b {
		t.Fatalf("ManifestHash not deterministic: %s != %s", a, b)
	}
	if len(a) != 64 {
		t.Fatalf("manifest hash len = %d, want 64 (sha256 hex)", len(a))
	}
}

func TestManifestHashChangesWhenFileChanges(t *testing.T) {
	c, err := Open(filepath.Join(t.TempDir(), "c.sqlite"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer c.Close()
	_ = c.Put(&Entry{ContentHash: "h1", Path: "a.go", Language: "go", ParsedAt: "t"})
	before, _ := c.ManifestHash()
	if err := c.PurgeByPath("a.go"); err != nil {
		t.Fatal(err)
	}
	_ = c.Put(&Entry{ContentHash: "h2", Path: "a.go", Language: "go", ParsedAt: "t"})
	after, _ := c.ManifestHash()
	if before == after {
		t.Fatal("ManifestHash unchanged after file mutation")
	}
}

func TestManifestHashEmptyCache(t *testing.T) {
	c, err := Open(filepath.Join(t.TempDir(), "c.sqlite"))
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	defer c.Close()
	h, _ := c.ManifestHash()
	want := "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
	if h != want {
		t.Fatalf("empty manifest = %q, want %q", h, want)
	}
}

func TestManifestHashIndependentOfInsertOrder(t *testing.T) {
	a, err := Open(filepath.Join(t.TempDir(), "a.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	b, err := Open(filepath.Join(t.TempDir(), "b.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer b.Close()
	files := []*Entry{
		{ContentHash: "h1", Path: "a.go", Language: "go", ParsedAt: "t"},
		{ContentHash: "h2", Path: "b.go", Language: "go", ParsedAt: "t"},
		{ContentHash: "h3", Path: "c.go", Language: "go", ParsedAt: "t"},
	}
	for _, e := range files {
		_ = a.Put(e)
	}
	for i := len(files) - 1; i >= 0; i-- {
		_ = b.Put(files[i])
	}
	ha, _ := a.ManifestHash()
	hb, _ := b.ManifestHash()
	if ha != hb {
		t.Fatalf("manifest depends on insert order: %s != %s", ha, hb)
	}
}
