package mcp_test

import (
	"reflect"
	"sort"
	"strings"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/flow"
	"github.com/randomcodespace/codeiq/go/internal/mcp"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// flowFixtureDeps wires an in-memory Flow engine over a minimal
// snapshot so tool tests stay CGO-free for the flow case. The Java
// FlowEngine has the same shape — it accepts a pre-loaded snapshot
// (CacheFlowDataSource) without needing the database open.
func flowFixtureDeps() *mcp.Deps {
	nodes := []*model.CodeNode{
		{ID: "svc:a", Kind: model.NodeService, Label: "serviceA", Layer: model.LayerBackend},
		{ID: "cls:b", Kind: model.NodeClass, Label: "B", Layer: model.LayerBackend, FilePath: "B.java"},
	}
	edges := []*model.CodeEdge{
		{ID: "e1", Kind: model.EdgeContains, SourceID: "svc:a", TargetID: "cls:b"},
	}
	snap := flow.NewSnapshot(nodes, edges)
	return &mcp.Deps{Flow: flow.NewEngineFromSnapshot(snap)}
}

func TestRegisterFlowRegistersOne(t *testing.T) {
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterFlow(srv, &mcp.Deps{}); err != nil {
		t.Fatalf("RegisterFlow: %v", err)
	}
	got := srv.Registry().Names()
	want := []string{"generate_flow"}
	sort.Strings(got)
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("flow tools = %v, want %v", got, want)
	}
}

func TestGenerateFlowJSONDefault(t *testing.T) {
	d := flowFixtureDeps()
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterFlow(srv, d); err != nil {
		t.Fatalf("RegisterFlow: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()
	ctx, cancel := contextDeadline(t)
	defer cancel()

	res, err := sess.CallTool(ctx, sdkCallToolParams("generate_flow", nil))
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	tc, ok := res.Content[0].(textContent)
	if !ok {
		t.Fatalf("content type = %T", res.Content[0])
	}
	// Default format is JSON — body should parse and contain a `view`.
	out := unmarshalJSON(t, tc.Text)
	if out["view"] != "overview" {
		t.Fatalf("view = %v, want overview. body=%s", out["view"], tc.Text)
	}
}

func TestGenerateFlowMermaidContainsHeader(t *testing.T) {
	d := flowFixtureDeps()
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterFlow(srv, d); err != nil {
		t.Fatalf("RegisterFlow: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()
	ctx, cancel := contextDeadline(t)
	defer cancel()

	res, err := sess.CallTool(ctx, sdkCallToolParams("generate_flow", map[string]any{
		"view":   "overview",
		"format": "mermaid",
	}))
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	tc, ok := res.Content[0].(textContent)
	if !ok {
		t.Fatalf("content type = %T", res.Content[0])
	}
	if !strings.HasPrefix(strings.TrimSpace(tc.Text), "graph LR") {
		t.Fatalf("mermaid body missing graph LR header.\n%s", tc.Text)
	}
}

func TestGenerateFlowRejectsUnknownView(t *testing.T) {
	d := flowFixtureDeps()
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterFlow(srv, d); err != nil {
		t.Fatalf("RegisterFlow: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()
	ctx, cancel := contextDeadline(t)
	defer cancel()

	res, err := sess.CallTool(ctx, sdkCallToolParams("generate_flow", map[string]any{"view": "bogus"}))
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	tc := res.Content[0].(textContent)
	out := unmarshalJSON(t, tc.Text)
	if out["code"] != mcp.CodeInvalidInput {
		t.Fatalf("code = %v, want INVALID_INPUT. body=%s", out["code"], tc.Text)
	}
}

func TestGenerateFlowDisabledWithoutEngine(t *testing.T) {
	d := &mcp.Deps{}
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterFlow(srv, d); err != nil {
		t.Fatalf("RegisterFlow: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()
	ctx, cancel := contextDeadline(t)
	defer cancel()

	res, err := sess.CallTool(ctx, sdkCallToolParams("generate_flow", nil))
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	tc := res.Content[0].(textContent)
	out := unmarshalJSON(t, tc.Text)
	if _, hasErr := out["error"]; !hasErr {
		t.Fatalf("expected error envelope for missing engine, got %v", out)
	}
}
