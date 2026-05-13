package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestIniStructureDetector_Positive(t *testing.T) {
	d := NewIniStructureDetector()
	ctx := &detector.Context{
		FilePath: "config.ini",
		Language: "ini",
		ParsedData: map[string]any{
			"type": "ini",
			"data": map[string]any{
				"database": map[string]any{"host": "localhost", "port": "5432"},
				"logging":  map[string]any{"level": "info"},
			},
		},
	}
	r := d.Detect(ctx)
	// 1 file + 2 sections + 3 keys = 6 nodes
	if len(r.Nodes) != 6 {
		t.Fatalf("expected 6 nodes, got %d", len(r.Nodes))
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
}

func TestIniStructureDetector_NegativeWrongType(t *testing.T) {
	d := NewIniStructureDetector()
	ctx := &detector.Context{
		FilePath: "config.ini",
		Language: "ini",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{"key": "value"},
		},
	}
	r := d.Detect(ctx)
	// Just the file node
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 node, got %d", len(r.Nodes))
	}
}

func TestIniStructureDetector_Deterministic(t *testing.T) {
	d := NewIniStructureDetector()
	ctx := &detector.Context{
		FilePath: "t.ini",
		Language: "ini",
		ParsedData: map[string]any{
			"type": "ini",
			"data": map[string]any{"section": map[string]any{"key": "value"}},
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
