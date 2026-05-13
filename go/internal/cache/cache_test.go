package cache

import (
	"path/filepath"
	"testing"
	"time"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestCacheRoundTrip(t *testing.T) {
	dir := t.TempDir()
	c, err := Open(filepath.Join(dir, "test.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()

	hash := "deadbeef"
	nodes := []*model.CodeNode{
		model.NewCodeNode("file.java:Foo", model.NodeClass, "Foo"),
	}
	nodes[0].FilePath = "file.java"
	nodes[0].Source = "SpringRestDetector"

	edges := []*model.CodeEdge{
		model.NewCodeEdge("file.java:Foo->Bar", model.EdgeCalls,
			"file.java:Foo", "file.java:Bar"),
	}

	entry := &Entry{
		ContentHash: hash,
		Path:        "file.java",
		Language:    "java",
		ParsedAt:    time.Now().UTC().Format(time.RFC3339),
		Nodes:       nodes,
		Edges:       edges,
	}
	if err := c.Put(entry); err != nil {
		t.Fatal(err)
	}
	if !c.Has(hash) {
		t.Fatal("Has should return true after Put")
	}
	got, err := c.Get(hash)
	if err != nil {
		t.Fatal(err)
	}
	if got.Path != entry.Path || got.Language != entry.Language {
		t.Fatalf("metadata mismatch: %+v", got)
	}
	if len(got.Nodes) != 1 || got.Nodes[0].ID != "file.java:Foo" {
		t.Fatalf("node round-trip: %+v", got.Nodes)
	}
	if len(got.Edges) != 1 || got.Edges[0].Kind != model.EdgeCalls {
		t.Fatalf("edge round-trip: %+v", got.Edges)
	}
}

func TestCacheVersionStamped(t *testing.T) {
	dir := t.TempDir()
	c, err := Open(filepath.Join(dir, "v.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	v, err := c.Version()
	if err != nil {
		t.Fatal(err)
	}
	if v != CacheVersion {
		t.Fatalf("Version() = %d, want %d", v, CacheVersion)
	}
}

func TestCacheMissReturnsErrNotFound(t *testing.T) {
	dir := t.TempDir()
	c, _ := Open(filepath.Join(dir, "m.sqlite"))
	defer c.Close()
	_, err := c.Get("nope")
	if err != ErrNotFound {
		t.Fatalf("Get(missing) err = %v, want ErrNotFound", err)
	}
}
