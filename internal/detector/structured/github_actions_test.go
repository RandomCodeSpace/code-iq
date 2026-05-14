package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

func TestGitHubActionsDetector_Positive(t *testing.T) {
	d := NewGitHubActionsDetector()
	ctx := &detector.Context{
		FilePath: ".github/workflows/ci.yml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"name": "CI",
				"on":   map[string]any{"push": map[string]any{}},
				"jobs": map[string]any{"build": map[string]any{"runs-on": "ubuntu-latest"}},
			},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 3 {
		t.Fatalf("expected 3 nodes (workflow + trigger + job), got %d", len(r.Nodes))
	}
	var sawModule, sawMethod bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeModule {
			sawModule = true
		}
		if n.Kind == model.NodeMethod {
			sawMethod = true
		}
	}
	if !sawModule || !sawMethod {
		t.Errorf("missing kinds: module=%v method=%v", sawModule, sawMethod)
	}
}

func TestGitHubActionsDetector_JobDependencies(t *testing.T) {
	d := NewGitHubActionsDetector()
	ctx := &detector.Context{
		FilePath: ".github/workflows/ci.yml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"name": "CI",
				"on":   "push",
				"jobs": map[string]any{
					"build":  map[string]any{"runs-on": "ubuntu-latest"},
					"deploy": map[string]any{"runs-on": "ubuntu-latest", "needs": "build"},
				},
			},
		},
	}
	r := d.Detect(ctx)
	var sawDep bool
	for _, e := range r.Edges {
		if e.Kind == model.EdgeDependsOn {
			sawDep = true
		}
	}
	if !sawDep {
		t.Fatal("missing DEPENDS_ON edge")
	}
}

func TestGitHubActionsDetector_NotWorkflowPath(t *testing.T) {
	d := NewGitHubActionsDetector()
	ctx := &detector.Context{
		FilePath: "config.yml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{"name": "CI", "on": "push"},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestGitHubActionsDetector_Deterministic(t *testing.T) {
	d := NewGitHubActionsDetector()
	ctx := &detector.Context{
		FilePath: ".github/workflows/ci.yml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"name": "CI",
				"on":   []any{"push", "pull_request"},
				"jobs": map[string]any{"build": map[string]any{"runs-on": "ubuntu-latest"}},
			},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
