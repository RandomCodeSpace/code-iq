package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestTomlStructureDetector_Positive(t *testing.T) {
	d := NewTomlStructureDetector()
	ctx := &detector.Context{
		FilePath: "config.toml",
		Language: "toml",
		ParsedData: map[string]any{
			"type": "toml",
			"data": map[string]any{
				"title":    "My Config",
				"database": map[string]any{"host": "localhost", "port": 5432},
			},
		},
	}
	r := d.Detect(ctx)
	// 1 file + 2 top-level keys (title, database)
	if len(r.Nodes) != 3 {
		t.Fatalf("expected 3 nodes, got %d", len(r.Nodes))
	}
	// database key node should have section=true
	var dbNode *model.CodeNode
	for _, n := range r.Nodes {
		if n.Label == "database" {
			dbNode = n
		}
	}
	if dbNode == nil {
		t.Fatal("missing database node")
	}
	if got, _ := dbNode.Properties["section"].(bool); !got {
		t.Errorf("database node should have section=true")
	}
}

func TestTomlStructureDetector_NegativeNoParsedData(t *testing.T) {
	d := NewTomlStructureDetector()
	ctx := &detector.Context{FilePath: "config.toml", Language: "toml"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 node, got %d", len(r.Nodes))
	}
}

func TestTomlStructureDetector_Deterministic(t *testing.T) {
	d := NewTomlStructureDetector()
	ctx := &detector.Context{
		FilePath: "t.toml",
		Language: "toml",
		ParsedData: map[string]any{
			"type": "toml",
			"data": map[string]any{"a": "1", "b": map[string]any{"c": "2"}},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic")
		}
	}
}
