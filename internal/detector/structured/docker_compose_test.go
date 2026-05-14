package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

func TestDockerComposeDetector_Positive(t *testing.T) {
	d := NewDockerComposeDetector()
	ctx := &detector.Context{
		FilePath: "docker-compose.yml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"services": map[string]any{
					"web": map[string]any{"image": "nginx", "ports": []any{"8080:80"}},
					"db":  map[string]any{"image": "postgres"},
				},
			},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
	var sawInfra bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeInfraResource {
			sawInfra = true
		}
	}
	if !sawInfra {
		t.Fatal("missing INFRA_RESOURCE node")
	}
	if len(r.Nodes) != 3 {
		t.Errorf("expected 3 nodes (2 services + 1 port), got %d", len(r.Nodes))
	}
}

func TestDockerComposeDetector_DependsOn(t *testing.T) {
	d := NewDockerComposeDetector()
	ctx := &detector.Context{
		FilePath: "docker-compose.yml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"services": map[string]any{
					"web": map[string]any{"image": "nginx", "depends_on": []any{"db"}},
					"db":  map[string]any{"image": "postgres"},
				},
			},
		},
	}
	r := d.Detect(ctx)
	if len(r.Edges) != 1 {
		t.Fatalf("expected 1 edge, got %d", len(r.Edges))
	}
	if r.Edges[0].Kind != model.EdgeDependsOn {
		t.Errorf("kind = %v, want DEPENDS_ON", r.Edges[0].Kind)
	}
}

func TestDockerComposeDetector_NotCompose(t *testing.T) {
	d := NewDockerComposeDetector()
	ctx := &detector.Context{
		FilePath: "config.yaml",
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

func TestDockerComposeDetector_Deterministic(t *testing.T) {
	d := NewDockerComposeDetector()
	ctx := &detector.Context{
		FilePath: "docker-compose.yml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"services": map[string]any{
					"web": map[string]any{"image": "nginx"},
					"db":  map[string]any{"image": "postgres"},
				},
			},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
