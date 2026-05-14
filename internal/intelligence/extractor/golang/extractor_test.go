package golang

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/intelligence/extractor"
	"github.com/randomcodespace/codeiq/internal/model"
)

func TestExtractor_Language(t *testing.T) {
	if got := New().Language(); got != "go" {
		t.Fatalf("Language() = %q, want %q", got, "go")
	}
}

func TestExtract_MethodBodyCallEdge(t *testing.T) {
	// Method declaration with a body call. The receiver method's name is
	// `Process`, the call inside is `validate()`. Registry has a matching
	// METHOD node for `validate`.
	src := `
package svc

func (h *Handler) Process() {
    validate()
}
`
	process := model.NewCodeNode("m:process", model.NodeMethod, "Process")
	validate := model.NewCodeNode("m:validate", model.NodeMethod, "validate")
	reg := map[string]*model.CodeNode{
		process.ID:  process,
		validate.ID: validate,
		"validate":  validate,
	}
	ctx := extractor.Context{
		FilePath: "svc/handler.go",
		Language: "go",
		Content:  src,
		Registry: reg,
	}
	r := New().Extract(ctx, process)
	if len(r.CallEdges) != 1 {
		t.Fatalf("CallEdges = %d, want 1: %+v", len(r.CallEdges), r.CallEdges)
	}
	e := r.CallEdges[0]
	if e.Kind != model.EdgeCalls || e.SourceID != process.ID || e.TargetID != validate.ID {
		t.Errorf("edge mismatch: %+v", e)
	}
	if got, _ := e.Properties["extractor_name"].(string); got != "go_language_extractor" {
		t.Errorf("extractor_name = %v, want go_language_extractor", e.Properties["extractor_name"])
	}
	if got, _ := e.Properties["confidence"].(string); got != "PARTIAL" {
		t.Errorf("confidence = %v, want PARTIAL", e.Properties["confidence"])
	}
}

func TestExtract_QualifiedCallStrippedToBaseName(t *testing.T) {
	// `log.Println(...)` should strip to `Println` and match a registry
	// METHOD node by that label.
	src := `
package svc

func DoStuff() {
    log.Println("hi")
}
`
	do := model.NewCodeNode("m:do", model.NodeMethod, "DoStuff")
	println := model.NewCodeNode("m:println", model.NodeMethod, "Println")
	reg := map[string]*model.CodeNode{
		do.ID:      do,
		println.ID: println,
		"Println":  println,
	}
	ctx := extractor.Context{
		FilePath: "svc/do.go",
		Language: "go",
		Content:  src,
		Registry: reg,
	}
	r := New().Extract(ctx, do)
	if len(r.CallEdges) != 1 {
		t.Fatalf("CallEdges = %d, want 1", len(r.CallEdges))
	}
	if r.CallEdges[0].TargetID != println.ID {
		t.Errorf("TargetID = %q, want %q", r.CallEdges[0].TargetID, println.ID)
	}
}

func TestExtract_InterfaceAssertionHint(t *testing.T) {
	src := `
package svc

type Foo struct{}

var _ io.Reader = (*Foo)(nil)
`
	foo := model.NewCodeNode("c:foo", model.NodeClass, "Foo")
	ctx := extractor.Context{
		FilePath: "svc/foo.go",
		Language: "go",
		Content:  src,
		Registry: map[string]*model.CodeNode{foo.ID: foo},
	}
	r := New().Extract(ctx, foo)
	if got := r.TypeHints["implements_types"]; got != "io.Reader" {
		t.Errorf("implements_types = %q, want %q", got, "io.Reader")
	}
}

func TestExtract_NonRelevantNodeReturnsEmpty(t *testing.T) {
	src := `package x
`
	n := model.NewCodeNode("e:x", model.NodeEntity, "X")
	ctx := extractor.Context{
		FilePath: "x.go",
		Language: "go",
		Content:  src,
		Registry: map[string]*model.CodeNode{n.ID: n},
	}
	r := New().Extract(ctx, n)
	if len(r.CallEdges) != 0 || len(r.TypeHints) != 0 {
		t.Errorf("ENTITY node should produce empty result; got %+v", r)
	}
}

func TestExtract_NoInterfaceAssertionForClassWithoutMatch(t *testing.T) {
	src := `package x

type Foo struct{}
`
	foo := model.NewCodeNode("c:foo", model.NodeClass, "Foo")
	ctx := extractor.Context{
		FilePath: "x.go",
		Language: "go",
		Content:  src,
		Registry: map[string]*model.CodeNode{foo.ID: foo},
	}
	r := New().Extract(ctx, foo)
	if len(r.TypeHints) != 0 {
		t.Errorf("class without iface assert should yield no hints; got %+v", r.TypeHints)
	}
}
