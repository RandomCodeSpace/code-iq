package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

func TestPackageJsonDetector_Positive(t *testing.T) {
	d := NewPackageJsonDetector()
	ctx := &detector.Context{
		FilePath: "package.json",
		Language: "json",
		ParsedData: map[string]any{
			"type": "json",
			"data": map[string]any{
				"name":         "my-app",
				"version":      "1.0.0",
				"dependencies": map[string]any{"express": "^4.18.0"},
				"scripts":      map[string]any{"start": "node index.js", "test": "jest"},
			},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 3 {
		t.Fatalf("expected 3 nodes, got %d", len(r.Nodes))
	}
	var sawModule, sawDep bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeModule {
			sawModule = true
		}
	}
	for _, e := range r.Edges {
		if e.Kind == model.EdgeDependsOn {
			sawDep = true
		}
	}
	if !sawModule || !sawDep {
		t.Errorf("module=%v dep=%v", sawModule, sawDep)
	}
}

func TestPackageJsonDetector_NotPackageJson(t *testing.T) {
	d := NewPackageJsonDetector()
	ctx := &detector.Context{
		FilePath: "config.json",
		Language: "json",
		ParsedData: map[string]any{
			"type": "json",
			"data": map[string]any{"name": "my-app"},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestPackageJsonDetector_Deterministic(t *testing.T) {
	d := NewPackageJsonDetector()
	ctx := &detector.Context{
		FilePath: "package.json",
		Language: "json",
		ParsedData: map[string]any{
			"type": "json",
			"data": map[string]any{"name": "pkg", "version": "1.0.0"},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
