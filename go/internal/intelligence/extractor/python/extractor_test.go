package python

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/intelligence/extractor"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestExtractor_Language(t *testing.T) {
	if got := New().Language(); got != "python" {
		t.Fatalf("Language() = %q, want %q", got, "python")
	}
}

func TestExtract_FunctionBodyCallEdge(t *testing.T) {
	src := `
def checkout():
    validate()
`
	checkout := model.NewCodeNode("m:checkout", model.NodeMethod, "checkout")
	validate := model.NewCodeNode("m:validate", model.NodeMethod, "validate")
	reg := map[string]*model.CodeNode{
		checkout.ID: checkout,
		validate.ID: validate,
		"validate":  validate,
	}
	ctx := extractor.Context{
		FilePath: "checkout.py",
		Language: "python",
		Content:  src,
		Registry: reg,
	}
	r := New().Extract(ctx, checkout)
	if len(r.CallEdges) != 1 {
		t.Fatalf("CallEdges = %d, want 1", len(r.CallEdges))
	}
	e := r.CallEdges[0]
	if e.Kind != model.EdgeCalls || e.SourceID != checkout.ID || e.TargetID != validate.ID {
		t.Errorf("edge mismatch: %+v", e)
	}
	if got, _ := e.Properties["extractor_name"].(string); got != "python_language_extractor" {
		t.Errorf("extractor_name = %v, want python_language_extractor", e.Properties["extractor_name"])
	}
	if got, _ := e.Properties["confidence"].(string); got != "PARTIAL" {
		t.Errorf("confidence = %v, want PARTIAL", e.Properties["confidence"])
	}
}

func TestExtract_ClassExtendsHint(t *testing.T) {
	src := `
class Foo(Bar):
    pass
`
	foo := model.NewCodeNode("c:foo", model.NodeClass, "Foo")
	ctx := extractor.Context{
		FilePath: "foo.py",
		Language: "python",
		Content:  src,
		Registry: map[string]*model.CodeNode{foo.ID: foo},
	}
	r := New().Extract(ctx, foo)
	if got := r.TypeHints["extends_type"]; got != "Bar" {
		t.Errorf("extends_type = %q, want %q", got, "Bar")
	}
}

func TestExtract_ModuleAllExportsHint(t *testing.T) {
	src := `__all__ = ["alpha", "beta", "gamma"]
`
	module := model.NewCodeNode("mod:m", model.NodeModule, "m")
	ctx := extractor.Context{
		FilePath: "m.py",
		Language: "python",
		Content:  src,
		Registry: map[string]*model.CodeNode{module.ID: module},
	}
	r := New().Extract(ctx, module)
	if got := r.TypeHints["all_exports"]; got != "alpha, beta, gamma" {
		t.Errorf("all_exports = %q, want \"alpha, beta, gamma\"", got)
	}
}

func TestExtract_ModuleNoAllListReturnsEmpty(t *testing.T) {
	module := model.NewCodeNode("mod:m", model.NodeModule, "m")
	ctx := extractor.Context{
		FilePath: "m.py",
		Language: "python",
		Content:  "print('hi')\n",
		Registry: map[string]*model.CodeNode{module.ID: module},
	}
	r := New().Extract(ctx, module)
	if len(r.TypeHints) != 0 {
		t.Errorf("module without __all__ should produce no type-hints; got %+v", r.TypeHints)
	}
}

func TestExtract_ClassWithoutBaseReturnsEmpty(t *testing.T) {
	src := `class Foo:
    pass
`
	foo := model.NewCodeNode("c:foo", model.NodeClass, "Foo")
	ctx := extractor.Context{
		FilePath: "foo.py",
		Language: "python",
		Content:  src,
		Registry: map[string]*model.CodeNode{foo.ID: foo},
	}
	r := New().Extract(ctx, foo)
	if len(r.TypeHints) != 0 {
		t.Errorf("base-less class should produce no type-hints; got %+v", r.TypeHints)
	}
}

func TestExtract_NonRelevantNodeReturnsEmpty(t *testing.T) {
	r := New().Extract(extractor.Context{
		FilePath: "x.py",
		Language: "python",
		Content:  "x = 1\n",
		Registry: map[string]*model.CodeNode{},
	}, model.NewCodeNode("x:1", model.NodeEntity, "X"))
	if len(r.CallEdges) != 0 || len(r.TypeHints) != 0 {
		t.Errorf("ENTITY node should yield empty result; got %+v", r)
	}
}
