package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestKubernetesRbacDetector_RoleAndBinding(t *testing.T) {
	d := NewKubernetesRbacDetector()
	ctx := &detector.Context{
		FilePath: "rbac.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml_multi",
			"documents": []any{
				map[string]any{
					"kind":     "Role",
					"metadata": map[string]any{"name": "pod-reader", "namespace": "default"},
					"rules": []any{map[string]any{
						"apiGroups": []any{""},
						"resources": []any{"pods"},
						"verbs":     []any{"get", "list"},
					}},
				},
				map[string]any{
					"kind":     "ServiceAccount",
					"metadata": map[string]any{"name": "my-sa", "namespace": "default"},
				},
				map[string]any{
					"kind":     "RoleBinding",
					"metadata": map[string]any{"name": "read-pods", "namespace": "default"},
					"roleRef":  map[string]any{"kind": "Role", "name": "pod-reader"},
					"subjects": []any{map[string]any{"kind": "ServiceAccount", "name": "my-sa", "namespace": "default"}},
				},
			},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 3 {
		t.Fatalf("expected 3 nodes, got %d", len(r.Nodes))
	}
	for _, n := range r.Nodes {
		if n.Kind != model.NodeGuard {
			t.Errorf("kind = %v, want GUARD", n.Kind)
		}
	}
	var sawProtects bool
	for _, e := range r.Edges {
		if e.Kind == model.EdgeProtects {
			sawProtects = true
		}
	}
	if !sawProtects {
		t.Fatal("missing PROTECTS edge")
	}
}

func TestKubernetesRbacDetector_NotRbac(t *testing.T) {
	d := NewKubernetesRbacDetector()
	ctx := &detector.Context{
		FilePath: "deploy.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{"kind": "Deployment", "metadata": map[string]any{"name": "web"}},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestKubernetesRbacDetector_Deterministic(t *testing.T) {
	d := NewKubernetesRbacDetector()
	ctx := &detector.Context{
		FilePath: "rbac.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"kind":     "ClusterRole",
				"metadata": map[string]any{"name": "admin"},
				"rules":    []any{map[string]any{"apiGroups": []any{"*"}, "resources": []any{"*"}, "verbs": []any{"*"}}},
			},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
