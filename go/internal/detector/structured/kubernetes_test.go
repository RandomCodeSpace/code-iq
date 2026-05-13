package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestKubernetesDetector_Deployment(t *testing.T) {
	d := NewKubernetesDetector()
	ctx := &detector.Context{
		FilePath: "k8s/deploy.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"kind":     "Deployment",
				"metadata": map[string]any{"name": "web-app", "namespace": "prod"},
				"spec": map[string]any{
					"template": map[string]any{
						"spec": map[string]any{
							"containers": []any{
								map[string]any{"name": "app", "image": "nginx:latest"},
							},
						},
					},
				},
			},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
	var sawInfra, sawCfgKey bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeInfraResource {
			sawInfra = true
		}
		if n.Kind == model.NodeConfigKey {
			sawCfgKey = true
		}
	}
	if !sawInfra || !sawCfgKey {
		t.Errorf("missing kinds: infra=%v cfgkey=%v", sawInfra, sawCfgKey)
	}
}

func TestKubernetesDetector_MultiDocumentServiceSelector(t *testing.T) {
	d := NewKubernetesDetector()
	ctx := &detector.Context{
		FilePath: "k8s/app.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml_multi",
			"documents": []any{
				map[string]any{
					"kind":     "Deployment",
					"metadata": map[string]any{"name": "web", "namespace": "default"},
					"spec": map[string]any{
						"selector": map[string]any{"matchLabels": map[string]any{"app": "web"}},
						"template": map[string]any{"spec": map[string]any{"containers": []any{}}},
					},
				},
				map[string]any{
					"kind":     "Service",
					"metadata": map[string]any{"name": "web-svc", "namespace": "default"},
					"spec":     map[string]any{"selector": map[string]any{"app": "web"}},
				},
			},
		},
	}
	r := d.Detect(ctx)
	// 2 resources
	if len(r.Nodes) != 2 {
		t.Fatalf("expected 2 resource nodes, got %d", len(r.Nodes))
	}
	if len(r.Edges) == 0 {
		t.Fatal("expected service-selector edge")
	}
}

func TestKubernetesDetector_NotK8s(t *testing.T) {
	d := NewKubernetesDetector()
	ctx := &detector.Context{
		FilePath: "config.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{"name": "not-k8s", "version": "1.0"},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestKubernetesDetector_Deterministic(t *testing.T) {
	d := NewKubernetesDetector()
	ctx := &detector.Context{
		FilePath: "k8s/pod.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"kind":     "Pod",
				"metadata": map[string]any{"name": "test-pod"},
				"spec":     map[string]any{"containers": []any{map[string]any{"name": "main", "image": "alpine"}}},
			},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatalf("non-deterministic")
	}
}
