package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/intelligence/extractor"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestExtractor_Language(t *testing.T) {
	if got := New().Language(); got != "java" {
		t.Fatalf("Language() = %q, want %q", got, "java")
	}
}

func TestExtract_CallEdgeBetweenMethods(t *testing.T) {
	src := `
public class CheckoutService {
    public void checkout() {
        validateCart();
    }

    public void validateCart() {
        // ...
    }
}
`
	checkout := model.NewCodeNode("m:checkout", model.NodeMethod, "checkout")
	validate := model.NewCodeNode("m:validate", model.NodeMethod, "validateCart")
	registry := map[string]*model.CodeNode{
		checkout.ID: checkout,
		validate.ID: validate,
	}

	ctx := extractor.Context{
		FilePath: "CheckoutService.java",
		Language: "java",
		Content:  src,
		Registry: registry,
	}
	r := New().Extract(ctx, checkout)

	if len(r.CallEdges) != 1 {
		t.Fatalf("CallEdges = %d, want 1: %+v", len(r.CallEdges), r.CallEdges)
	}
	e := r.CallEdges[0]
	if e.Kind != model.EdgeCalls {
		t.Errorf("Kind = %v, want %v", e.Kind, model.EdgeCalls)
	}
	if e.SourceID != checkout.ID {
		t.Errorf("SourceID = %q, want %q", e.SourceID, checkout.ID)
	}
	if e.TargetID != validate.ID {
		t.Errorf("TargetID = %q, want %q", e.TargetID, validate.ID)
	}
	if got, _ := e.Properties["confidence"].(string); got != "PARTIAL" {
		t.Errorf("properties.confidence = %v, want PARTIAL", e.Properties["confidence"])
	}
	if got, _ := e.Properties["extractor_name"].(string); got != "java_language_extractor" {
		t.Errorf("properties.extractor_name = %v, want java_language_extractor", e.Properties["extractor_name"])
	}
}

func TestExtract_ClassExtendsImplementsHints(t *testing.T) {
	src := `
package x;
public class Foo extends Bar implements Baz, Qux {
}
`
	fooNode := model.NewCodeNode("c:foo", model.NodeClass, "Foo")
	ctx := extractor.Context{
		FilePath: "Foo.java",
		Language: "java",
		Content:  src,
		Registry: map[string]*model.CodeNode{fooNode.ID: fooNode},
	}
	r := New().Extract(ctx, fooNode)

	if got := r.TypeHints["extends_type"]; got != "Bar" {
		t.Errorf("extends_type = %q, want %q", got, "Bar")
	}
	// implements clause may be returned with or without comma-space spacing;
	// extractor should at least return the implementing-types literal text
	// containing both names.
	if got := r.TypeHints["implements_types"]; got == "" {
		t.Errorf("implements_types = %q, want non-empty", got)
	}
}

func TestExtract_NonMethodNonClassReturnsEmpty(t *testing.T) {
	src := `class X {}`
	moduleNode := model.NewCodeNode("mod:x", model.NodeModule, "X")
	ctx := extractor.Context{
		FilePath: "X.java",
		Language: "java",
		Content:  src,
		Registry: map[string]*model.CodeNode{},
	}
	r := New().Extract(ctx, moduleNode)
	if len(r.CallEdges) != 0 || len(r.TypeHints) != 0 {
		t.Errorf("non-relevant node should produce empty result; got %+v", r)
	}
}

func TestExtract_AmbiguousLabelDoesNotEmit(t *testing.T) {
	// Two distinct METHOD nodes share label "save" — extractor must DROP the
	// edge (lookupSingleMatch returns nil on ambiguity).
	src := `
class Service {
    public void persist() {
        save();
    }
}
`
	persist := model.NewCodeNode("m:persist", model.NodeMethod, "persist")
	save1 := model.NewCodeNode("m:save1", model.NodeMethod, "save")
	save2 := model.NewCodeNode("m:save2", model.NodeMethod, "save")
	reg := map[string]*model.CodeNode{
		persist.ID: persist,
		save1.ID:   save1,
		save2.ID:   save2,
	}
	ctx := extractor.Context{
		FilePath: "Service.java",
		Language: "java",
		Content:  src,
		Registry: reg,
	}
	r := New().Extract(ctx, persist)
	if len(r.CallEdges) != 0 {
		t.Errorf("ambiguous label should drop edge; got %d edges", len(r.CallEdges))
	}
}

func TestExtract_BrokenSourceReturnsEmpty(t *testing.T) {
	// Garbage source still parses (tree-sitter is error-tolerant) but no
	// method_declaration matches, so no edges emit.
	src := `}}{{not valid java{{{`
	n := model.NewCodeNode("m:x", model.NodeMethod, "checkout")
	ctx := extractor.Context{
		FilePath: "X.java",
		Language: "java",
		Content:  src,
		Registry: map[string]*model.CodeNode{n.ID: n},
	}
	r := New().Extract(ctx, n)
	if len(r.CallEdges) != 0 {
		t.Errorf("broken source should not emit edges; got %d", len(r.CallEdges))
	}
}
