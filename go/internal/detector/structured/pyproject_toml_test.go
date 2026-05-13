package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestPyprojectTomlDetector_PEP621(t *testing.T) {
	d := NewPyprojectTomlDetector()
	ctx := &detector.Context{
		FilePath: "pyproject.toml",
		Language: "toml",
		ParsedData: map[string]any{
			"type": "toml",
			"data": map[string]any{
				"project": map[string]any{
					"name":         "my-pkg",
					"version":      "0.1.0",
					"dependencies": []any{"requests>=2.0", "click"},
					"scripts":      map[string]any{"cli": "my_pkg.main:app"},
				},
			},
		},
	}
	r := d.Detect(ctx)
	var sawModule, sawCfgDef bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeModule {
			sawModule = true
		}
		if n.Kind == model.NodeConfigDefinition {
			sawCfgDef = true
		}
	}
	if !sawModule || !sawCfgDef {
		t.Errorf("module=%v cfgdef=%v", sawModule, sawCfgDef)
	}
	var depCount int
	for _, e := range r.Edges {
		if e.Kind == model.EdgeDependsOn {
			depCount++
		}
	}
	if depCount != 2 {
		t.Errorf("DEPENDS_ON count = %d, want 2", depCount)
	}
}

func TestPyprojectTomlDetector_NotPyproject(t *testing.T) {
	d := NewPyprojectTomlDetector()
	ctx := &detector.Context{
		FilePath: "config.toml",
		Language: "toml",
		ParsedData: map[string]any{
			"type": "toml",
			"data": map[string]any{"key": "value"},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestPyprojectTomlDetector_ParseDepName(t *testing.T) {
	cases := map[string]string{
		"requests>=2.0":      "requests",
		"black[jupyter]>=22": "black",
		"numpy":              "numpy",
		"":                   "",
	}
	for in, want := range cases {
		got := parsePEPDepName(in)
		if got != want {
			t.Errorf("parseDepName(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestPyprojectTomlDetector_Deterministic(t *testing.T) {
	d := NewPyprojectTomlDetector()
	ctx := &detector.Context{
		FilePath: "pyproject.toml",
		Language: "toml",
		ParsedData: map[string]any{
			"type": "toml",
			"data": map[string]any{"project": map[string]any{"name": "pkg", "version": "1.0"}},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
