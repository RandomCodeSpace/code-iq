package parity

import (
	"path/filepath"
	"strings"
	"testing"

	"github.com/randomcodespace/codeiq/internal/cache"
	"github.com/randomcodespace/codeiq/internal/model"
)

func TestNormalizeIsSorted(t *testing.T) {
	dir := t.TempDir()
	c, err := cache.Open(filepath.Join(dir, "c.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()

	// Two entries inserted out of order.
	for _, e := range []*cache.Entry{
		{
			ContentHash: "bb", Path: "z.java", Language: "java", ParsedAt: "2026-01-01T00:00:00Z",
			Nodes: []*model.CodeNode{model.NewCodeNode("z", model.NodeClass, "Z")},
		},
		{
			ContentHash: "aa", Path: "a.java", Language: "java", ParsedAt: "2026-01-01T00:00:00Z",
			Nodes: []*model.CodeNode{model.NewCodeNode("a", model.NodeClass, "A")},
		},
	} {
		if err := c.Put(e); err != nil {
			t.Fatal(err)
		}
	}
	out, err := Normalize(c)
	if err != nil {
		t.Fatal(err)
	}
	// "a.java" should appear before "z.java" in the rendered JSON.
	if !strings.Contains(out, `"a.java"`) || !strings.Contains(out, `"z.java"`) {
		t.Fatalf("missing entries in output:\n%s", out)
	}
	if strings.Index(out, `"a.java"`) > strings.Index(out, `"z.java"`) {
		t.Fatalf("entries not sorted:\n%s", out)
	}
}
