package mcp

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
)

// TestRegisterConsolidated_AllSixToolsLand verifies the six new tool names
// appear in the server registry after RegisterConsolidated runs.
func TestRegisterConsolidated_AllSixToolsLand(t *testing.T) {
	srv, err := NewServer(ServerOptions{Name: "test", Version: "0.0.0"})
	if err != nil {
		t.Fatal(err)
	}
	if err := RegisterConsolidated(srv, &Deps{}); err != nil {
		t.Fatalf("RegisterConsolidated: %v", err)
	}
	names := srv.Registry().Names()
	want := []string{
		"graph_summary",
		"find_in_graph",
		"inspect_node",
		"trace_relationships",
		"analyze_impact",
		"topology_view",
	}
	for _, w := range want {
		found := false
		for _, n := range names {
			if n == w {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("missing tool %q in registry; got %v", w, names)
		}
	}
}

// TestConsolidatedTool_UnknownModeRejected — each consolidated tool returns
// a CodeInvalidInput envelope when the mode is unrecognized.
func TestConsolidatedTool_UnknownModeRejected(t *testing.T) {
	d := &Deps{}
	for _, build := range []func(*Deps) Tool{
		toolGraphSummary, toolFindInGraph, toolInspectNode,
		toolTraceRelationships, toolAnalyzeImpact, toolTopologyView,
	} {
		tool := build(d)
		params, _ := json.Marshal(map[string]string{"mode": "bogus"})
		got, _ := tool.Handler(context.Background(), params)
		gotJSON, _ := json.Marshal(got)
		if !strings.Contains(string(gotJSON), "INVALID_INPUT") {
			t.Errorf("%s with bogus mode: expected INVALID_INPUT envelope, got %s", tool.Name, gotJSON)
		}
	}
}

// TestGraphSummary_DefaultModeIsOverview — mode unset routes to get_stats.
func TestGraphSummary_DefaultModeIsOverview(t *testing.T) {
	d := &Deps{}
	tool := toolGraphSummary(d)
	got, _ := tool.Handler(context.Background(), json.RawMessage(`{}`))
	gotJSON, _ := json.Marshal(got)
	// With no Stats wired, overview falls into the "stats service not wired"
	// envelope. Any other path would have been routed to capabilities or
	// provenance with a different envelope wording.
	if !strings.Contains(string(gotJSON), "stats service not wired") {
		t.Errorf("default mode did not route to overview: %s", gotJSON)
	}
}
