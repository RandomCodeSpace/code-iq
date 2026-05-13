package typescript

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/intelligence/extractor"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestExtractor_Language(t *testing.T) {
	if got := New().Language(); got != "typescript" {
		t.Fatalf("Language() = %q, want %q", got, "typescript")
	}
}

func TestExtract_CallEdgeFromExportedHandler(t *testing.T) {
	src := `
export default function handler() {
    auth();
}
`
	handler := model.NewCodeNode("m:handler", model.NodeMethod, "handler")
	auth := model.NewCodeNode("m:auth", model.NodeMethod, "auth")
	reg := map[string]*model.CodeNode{
		handler.ID: handler,
		auth.ID:    auth,
		// Also register by FQN so callee lookup hits — orchestrator does the
		// same in buildRegistry.
		"auth": auth,
	}
	ctx := extractor.Context{
		FilePath: "handler.ts",
		Language: "typescript",
		Content:  src,
		Registry: reg,
	}
	r := New().Extract(ctx, handler)
	if len(r.CallEdges) != 1 {
		t.Fatalf("CallEdges = %d, want 1", len(r.CallEdges))
	}
	e := r.CallEdges[0]
	if e.Kind != model.EdgeCalls {
		t.Errorf("Kind = %v, want %v", e.Kind, model.EdgeCalls)
	}
	if e.SourceID != handler.ID || e.TargetID != auth.ID {
		t.Errorf("edge = %s->%s, want %s->%s", e.SourceID, e.TargetID, handler.ID, auth.ID)
	}
	if got, _ := e.Properties["extractor_name"].(string); got != "typescript_language_extractor" {
		t.Errorf("extractor_name = %v, want typescript_language_extractor", e.Properties["extractor_name"])
	}
	if got, _ := e.Properties["confidence"].(string); got != "PARTIAL" {
		t.Errorf("confidence = %v, want PARTIAL", e.Properties["confidence"])
	}
}

func TestExtract_ModuleExportsHint(t *testing.T) {
	src := `
export function foo() {}
export const bar = 1;
`
	module := model.NewCodeNode("mod:m", model.NodeModule, "m")
	ctx := extractor.Context{
		FilePath: "m.ts",
		Language: "typescript",
		Content:  src,
		Registry: map[string]*model.CodeNode{module.ID: module},
	}
	r := New().Extract(ctx, module)
	if got := r.TypeHints["module_exports"]; got == "" {
		t.Fatalf("module_exports type-hint = empty, want non-empty (found: %+v)", r.TypeHints)
	}
}

func TestExtract_NonRelevantNodeReturnsEmpty(t *testing.T) {
	src := `export const x = 1;`
	cls := model.NewCodeNode("c:x", model.NodeClass, "X")
	ctx := extractor.Context{
		FilePath: "x.ts",
		Language: "typescript",
		Content:  src,
		Registry: map[string]*model.CodeNode{cls.ID: cls},
	}
	r := New().Extract(ctx, cls)
	if len(r.CallEdges) != 0 || len(r.TypeHints) != 0 {
		t.Errorf("class node should produce empty TS result; got %+v", r)
	}
}
