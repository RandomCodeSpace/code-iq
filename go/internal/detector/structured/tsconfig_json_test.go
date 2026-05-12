package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestTsconfigJsonDetector_Positive(t *testing.T) {
	d := NewTsconfigJsonDetector()
	ctx := &detector.Context{
		FilePath: "tsconfig.json",
		Language: "json",
		ParsedData: map[string]any{
			"type": "json",
			"data": map[string]any{
				"extends": "@tsconfig/node18/tsconfig.json",
				"compilerOptions": map[string]any{
					"strict": true,
					"target": "ES2022",
					"outDir": "./dist",
				},
				"references": []any{map[string]any{"path": "./packages/core"}},
			},
		},
	}
	r := d.Detect(ctx)
	// 1 config file + 3 compiler options
	if len(r.Nodes) != 4 {
		t.Fatalf("expected 4 nodes, got %d", len(r.Nodes))
	}
	var sawFile bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeConfigFile {
			sawFile = true
		}
	}
	if !sawFile {
		t.Fatal("missing CONFIG_FILE")
	}
	// 1 extends + 1 reference + 3 contains = 5 edges
	if len(r.Edges) != 5 {
		t.Errorf("expected 5 edges, got %d", len(r.Edges))
	}
	var sawDep bool
	for _, e := range r.Edges {
		if e.Kind == model.EdgeDependsOn {
			sawDep = true
		}
	}
	if !sawDep {
		t.Errorf("missing DEPENDS_ON")
	}
}

func TestTsconfigJsonDetector_NotTsconfig(t *testing.T) {
	d := NewTsconfigJsonDetector()
	ctx := &detector.Context{
		FilePath: "config.json",
		Language: "json",
		ParsedData: map[string]any{
			"type": "json",
			"data": map[string]any{"key": "value"},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestTsconfigJsonDetector_Deterministic(t *testing.T) {
	d := NewTsconfigJsonDetector()
	ctx := &detector.Context{
		FilePath: "tsconfig.json",
		Language: "json",
		ParsedData: map[string]any{
			"type": "json",
			"data": map[string]any{"compilerOptions": map[string]any{"strict": true}},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
