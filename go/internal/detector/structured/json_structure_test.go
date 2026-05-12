package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestJsonStructureDetector_Positive(t *testing.T) {
	d := NewJsonStructureDetector()
	ctx := &detector.Context{
		FilePath: "config.json",
		Language: "json",
		ParsedData: map[string]any{
			"type": "json",
			"data": map[string]any{
				"name":    "app",
				"version": "1.0",
				"main":    "index.js",
			},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 4 {
		t.Fatalf("expected 4 nodes (1 file + 3 keys), got %d", len(r.Nodes))
	}
	var sawFile bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeConfigFile {
			sawFile = true
		}
	}
	if !sawFile {
		t.Fatalf("missing CONFIG_FILE node")
	}
	if len(r.Edges) != 3 {
		t.Fatalf("expected 3 CONTAINS edges, got %d", len(r.Edges))
	}
}

func TestJsonStructureDetector_NegativeNoParsedData(t *testing.T) {
	d := NewJsonStructureDetector()
	ctx := &detector.Context{
		FilePath: "config.json",
		Language: "json",
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 node (file only), got %d", len(r.Nodes))
	}
	if len(r.Edges) != 0 {
		t.Fatalf("expected 0 edges, got %d", len(r.Edges))
	}
}

func TestJsonStructureDetector_Deterministic(t *testing.T) {
	d := NewJsonStructureDetector()
	ctx := &detector.Context{
		FilePath: "t.json",
		Language: "json",
		ParsedData: map[string]any{
			"type": "json",
			"data": map[string]any{"a": "1", "b": "2"},
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
