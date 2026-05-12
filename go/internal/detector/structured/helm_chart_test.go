package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestHelmChartDetector_ChartYaml(t *testing.T) {
	d := NewHelmChartDetector()
	ctx := &detector.Context{
		FilePath: "charts/my-app/Chart.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"name":    "my-app",
				"version": "1.0.0",
				"dependencies": []any{
					map[string]any{"name": "redis", "version": "17.0.0", "repository": "https://charts.bitnami.com/bitnami"},
				},
			},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 2 {
		t.Fatalf("expected 2 nodes, got %d", len(r.Nodes))
	}
	for _, n := range r.Nodes {
		if n.Kind != model.NodeModule {
			t.Errorf("kind = %v, want MODULE", n.Kind)
		}
	}
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

func TestHelmChartDetector_Template(t *testing.T) {
	content := `apiVersion: v1
kind: Service
metadata:
  name: {{ .Values.service.name }}
spec:
  type: {{ .Values.service.type }}
  ports:
    - port: {{ .Values.service.port }}
  selector:
    {{- include "my-app.selectorLabels" . | nindent 4 }}
`
	d := NewHelmChartDetector()
	ctx := &detector.Context{
		FilePath: "charts/my-app/templates/service.yaml",
		Language: "yaml",
		Content:  content,
	}
	r := d.Detect(ctx)
	var readsCount, importsCount int
	for _, e := range r.Edges {
		if e.Kind == model.EdgeReadsConfig {
			readsCount++
		}
		if e.Kind == model.EdgeImports {
			importsCount++
		}
	}
	if readsCount != 3 {
		t.Errorf("reads_config edges = %d, want 3", readsCount)
	}
	if importsCount != 1 {
		t.Errorf("imports edges = %d, want 1", importsCount)
	}
}

func TestHelmChartDetector_NotHelm(t *testing.T) {
	d := NewHelmChartDetector()
	ctx := &detector.Context{
		FilePath: "config.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{"key": "value"},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 || len(r.Edges) != 0 {
		t.Fatalf("expected empty result")
	}
}

func TestHelmChartDetector_Deterministic(t *testing.T) {
	d := NewHelmChartDetector()
	ctx := &detector.Context{
		FilePath: "charts/my/Chart.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{"name": "chart", "version": "1.0.0"},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
