package analyzer

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/model"
)

// TestLayerClassifierRules covers one positive case per priority rule:
// kind → language → file path → framework → shared → fallback path.
func TestLayerClassifierRules(t *testing.T) {
	lc := &LayerClassifier{}

	cases := []struct {
		name string
		node *model.CodeNode
		want model.Layer
	}{
		{
			name: "frontend node kind (component)",
			node: &model.CodeNode{
				Kind:       model.NodeComponent,
				Properties: map[string]any{},
			},
			want: model.LayerFrontend,
		},
		{
			name: "backend node kind (endpoint)",
			node: &model.CodeNode{
				Kind:       model.NodeEndpoint,
				Properties: map[string]any{},
			},
			want: model.LayerBackend,
		},
		{
			name: "infra by language (terraform)",
			node: &model.CodeNode{
				Kind:       model.NodeModule,
				Properties: map[string]any{"language": "terraform"},
			},
			want: model.LayerInfra,
		},
		{
			name: "file extension .tsx → frontend",
			node: &model.CodeNode{
				Kind:       model.NodeClass,
				FilePath:   "src/foo/Bar.tsx",
				Properties: map[string]any{},
			},
			want: model.LayerFrontend,
		},
		{
			name: "file path /server/ → backend",
			node: &model.CodeNode{
				Kind:       model.NodeClass,
				FilePath:   "src/server/handler.go",
				Properties: map[string]any{},
			},
			want: model.LayerBackend,
		},
		{
			name: "framework=react → frontend",
			node: &model.CodeNode{
				Kind:       model.NodeClass,
				FilePath:   "some/unrelated/path.js",
				Properties: map[string]any{"framework": "react"},
			},
			want: model.LayerFrontend,
		},
		{
			name: "shared node kind (config_file)",
			node: &model.CodeNode{
				Kind:       model.NodeConfigFile,
				Properties: map[string]any{},
			},
			want: model.LayerShared,
		},
		{
			name: "Java path fallback (src/main/java/...) → backend",
			node: &model.CodeNode{
				Kind:       model.NodeClass,
				FilePath:   "myapp/src/main/java/com/example/Greeter.java",
				Properties: map[string]any{},
			},
			want: model.LayerBackend,
		},
		{
			name: "fully unknown fallback",
			node: &model.CodeNode{
				Kind:       model.NodeClass,
				FilePath:   "random/path/file.txt",
				ID:         "rand:thing",
				Properties: map[string]any{},
			},
			want: model.LayerUnknown,
		},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := lc.classifyOne(tc.node)
			if got != tc.want {
				t.Fatalf("classifyOne(%s) = %s, want %s", tc.name, got, tc.want)
			}
		})
	}
}

// TestLayerClassifierClassifyMutates verifies Classify writes Layer on every node.
func TestLayerClassifierClassifyMutates(t *testing.T) {
	lc := &LayerClassifier{}
	nodes := []*model.CodeNode{
		{Kind: model.NodeComponent, Properties: map[string]any{}},
		{Kind: model.NodeEndpoint, Properties: map[string]any{}},
		{Kind: model.NodeClass, FilePath: "x.txt", Properties: map[string]any{}},
	}
	lc.Classify(nodes)
	want := []model.Layer{model.LayerFrontend, model.LayerBackend, model.LayerUnknown}
	for i, n := range nodes {
		if n.Layer != want[i] {
			t.Fatalf("node[%d].Layer = %s, want %s", i, n.Layer, want[i])
		}
	}
}

// TestLayerClassifierDeterminism runs the same input twice and asserts identical
// output — guards against accidental map iteration or non-deterministic logic.
func TestLayerClassifierDeterminism(t *testing.T) {
	lc := &LayerClassifier{}
	build := func() []*model.CodeNode {
		return []*model.CodeNode{
			{Kind: model.NodeComponent, Properties: map[string]any{}},
			{Kind: model.NodeEndpoint, Properties: map[string]any{}},
			{Kind: model.NodeModule, Properties: map[string]any{"language": "terraform"}},
			{Kind: model.NodeClass, FilePath: "src/foo/Bar.tsx", Properties: map[string]any{}},
			{Kind: model.NodeClass, FilePath: "src/server/handler.go", Properties: map[string]any{}},
			{Kind: model.NodeClass, FilePath: "x.js", Properties: map[string]any{"framework": "react"}},
			{Kind: model.NodeConfigFile, Properties: map[string]any{}},
			{Kind: model.NodeClass, FilePath: "myapp/src/main/java/com/Greeter.java", Properties: map[string]any{}},
			{Kind: model.NodeClass, FilePath: "random/path.txt", ID: "z", Properties: map[string]any{}},
		}
	}
	a := build()
	b := build()
	lc.Classify(a)
	lc.Classify(b)
	for i := range a {
		if a[i].Layer != b[i].Layer {
			t.Fatalf("non-deterministic Layer at index %d: %s vs %s", i, a[i].Layer, b[i].Layer)
		}
	}
}
