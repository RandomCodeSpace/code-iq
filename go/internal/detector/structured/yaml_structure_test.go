package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestYamlStructureDetector_PositiveSingleDoc(t *testing.T) {
	d := NewYamlStructureDetector()
	ctx := &detector.Context{
		FilePath: "config.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"name":    "app",
				"version": "1.0",
			},
		},
	}
	r := d.Detect(ctx)
	// 1 file node + 2 key nodes
	if len(r.Nodes) != 3 {
		t.Fatalf("expected 3 nodes, got %d", len(r.Nodes))
	}
	var sawFile bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeConfigFile {
			sawFile = true
		}
	}
	if !sawFile {
		t.Fatalf("missing CONFIG_FILE node: %+v", r.Nodes)
	}
}

func TestYamlStructureDetector_PositiveMultiDoc(t *testing.T) {
	d := NewYamlStructureDetector()
	ctx := &detector.Context{
		FilePath: "multi.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml_multi",
			"documents": []any{
				map[string]any{"key1": "val"},
				map[string]any{"key2": "val"},
			},
		},
	}
	r := d.Detect(ctx)
	// 1 file + 2 keys
	if len(r.Nodes) != 3 {
		t.Fatalf("expected 3 nodes, got %d", len(r.Nodes))
	}
}

func TestYamlStructureDetector_NegativeNoParsedData(t *testing.T) {
	d := NewYamlStructureDetector()
	ctx := &detector.Context{
		FilePath: "config.yaml",
		Language: "yaml",
	}
	r := d.Detect(ctx)
	// Still emits the file node.
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 node (file only), got %d", len(r.Nodes))
	}
}

func TestYamlStructureDetector_Deterministic(t *testing.T) {
	d := NewYamlStructureDetector()
	ctx := &detector.Context{
		FilePath: "t.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{"a": "1", "b": "2"},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("non-deterministic node counts")
	}
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic id at %d: %q vs %q", i, r1.Nodes[i].ID, r2.Nodes[i].ID)
		}
	}
}
