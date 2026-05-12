package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestGitLabCiDetector_Positive(t *testing.T) {
	d := NewGitLabCiDetector()
	ctx := &detector.Context{
		FilePath: ".gitlab-ci.yml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"stages":    []any{"build", "test", "deploy"},
				"build_job": map[string]any{"stage": "build", "script": []any{"docker build ."}},
				"test_job":  map[string]any{"stage": "test", "script": []any{"npm test"}, "needs": []any{"build_job"}},
			},
		},
	}
	r := d.Detect(ctx)
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
		t.Errorf("module=%v method=%v", sawModule, sawMethod)
	}
	var sawDep bool
	for _, e := range r.Edges {
		if e.Kind == model.EdgeDependsOn {
			sawDep = true
		}
	}
	if !sawDep {
		t.Fatal("missing DEPENDS_ON")
	}
}

func TestGitLabCiDetector_Tools(t *testing.T) {
	d := NewGitLabCiDetector()
	ctx := &detector.Context{
		FilePath: ".gitlab-ci.yml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"build_job": map[string]any{"script": []any{"docker build .", "helm package ."}},
			},
		},
	}
	r := d.Detect(ctx)
	var jobNode *model.CodeNode
	for _, n := range r.Nodes {
		if n.Kind == model.NodeMethod {
			jobNode = n
		}
	}
	if jobNode == nil {
		t.Fatal("missing job METHOD node")
	}
	tools, ok := jobNode.Properties["tools"].([]string)
	if !ok {
		t.Fatalf("tools not a []string, got %T: %+v", jobNode.Properties["tools"], jobNode.Properties)
	}
	var sawDocker, sawHelm bool
	for _, t := range tools {
		if t == "docker" {
			sawDocker = true
		}
		if t == "helm" {
			sawHelm = true
		}
	}
	if !sawDocker || !sawHelm {
		t.Errorf("docker=%v helm=%v", sawDocker, sawHelm)
	}
}

func TestGitLabCiDetector_NotGitlab(t *testing.T) {
	d := NewGitLabCiDetector()
	ctx := &detector.Context{
		FilePath: "config.yml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{"key": "value"},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestGitLabCiDetector_Deterministic(t *testing.T) {
	d := NewGitLabCiDetector()
	ctx := &detector.Context{
		FilePath: ".gitlab-ci.yml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"stages": []any{"build"},
				"job1":   map[string]any{"stage": "build", "script": []any{"echo hi"}},
			},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
