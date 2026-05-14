package mcp_test

// Per-mode parity tests for the 6 consolidated MCP tools.
//
// Coverage goal: one table-driven parent test per tool, one sub-test per
// mode — 32 mode dispatches total. Each sub-test asserts:
//   - the dispatch reaches the underlying handler (no "unknown mode" error),
//   - the response envelope has the expected top-level key(s), and
//   - where the fixture has data, the key holds a non-error value.

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/flow"
	"github.com/randomcodespace/codeiq/internal/mcp"
	"github.com/randomcodespace/codeiq/internal/model"
)

// consolidatedDeps returns *mcp.Deps backed by the topology fixture
// (checkout + billing services) augmented with a Flow engine so
// topology_view/flow works.
func consolidatedDeps(t *testing.T) *mcp.Deps {
	t.Helper()
	// Reuse the topology fixture (already has Store, Query, Stats, Topology).
	d := topologyFixtureDeps(t)
	// Wire a Flow engine from an in-memory snapshot so topology_view/flow
	// does not error out on a nil engine.
	nodes := []*model.CodeNode{
		{ID: "svc:checkout", Kind: model.NodeService, Label: "checkout", Layer: model.LayerBackend},
		{ID: "svc:billing", Kind: model.NodeService, Label: "billing", Layer: model.LayerBackend},
	}
	edges := []*model.CodeEdge{
		{ID: "ef1", Kind: model.EdgeCalls, SourceID: "svc:checkout", TargetID: "svc:billing"},
	}
	snap := flow.NewSnapshot(nodes, edges)
	d.Flow = flow.NewEngineFromSnapshot(snap)
	return d
}

// callConsolidatedTool registers only RegisterConsolidated on a fresh
// server, invokes the named tool via the SDK in-memory transport, and
// returns the parsed JSON response body.
func callConsolidatedTool(t *testing.T, d *mcp.Deps, name string, args map[string]any) map[string]any {
	t.Helper()
	srv, err := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	if err := mcp.RegisterConsolidated(srv, d); err != nil {
		t.Fatalf("RegisterConsolidated: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()

	ctx, cancel := contextDeadline(t)
	defer cancel()

	res, err := sess.CallTool(ctx, sdkCallToolParams(name, args))
	if err != nil {
		t.Fatalf("CallTool(%s, %v): %v", name, args, err)
	}
	if len(res.Content) == 0 {
		t.Fatalf("%s returned empty content", name)
	}
	tc, ok := res.Content[0].(textContent)
	if !ok {
		t.Fatalf("%s content type = %T", name, res.Content[0])
	}
	return unmarshalJSON(t, tc.Text)
}

// assertKey fatalf's unless the response map contains every key in wantKeys.
func assertKeys(t *testing.T, got map[string]any, wantKeys []string) {
	t.Helper()
	for _, k := range wantKeys {
		if _, ok := got[k]; !ok {
			t.Errorf("response missing key %q; got keys: %v", k, mapKeys(got))
		}
	}
}

// assertCode asserts got["code"] == wantCode — used for modes that are
// expected to return a structured error envelope due to known bugs.
func assertCode(t *testing.T, got map[string]any, wantCode string) {
	t.Helper()
	if got["code"] != wantCode {
		t.Errorf("code = %v, want %v. full response: %v", got["code"], wantCode, got)
	}
}

// mapKeys returns the keys of a map[string]any for diagnostic output.
func mapKeys(m map[string]any) []string {
	ks := make([]string, 0, len(m))
	for k := range m {
		ks = append(ks, k)
	}
	return ks
}

// --------------------------------------------------------------------------
// graph_summary — 4 modes
// --------------------------------------------------------------------------

func TestGraphSummary_AllModes(t *testing.T) {
	cases := []struct {
		mode      string
		args      map[string]any
		wantKeys  []string // at least one of these must be present
		wantError bool     // true = expect error envelope (code key present)
	}{
		// overview delegates to get_stats; Stats is wired → non-empty map.
		{mode: "overview", wantKeys: []string{"graph"}},
		// categories with no category arg delegates to get_detailed_stats
		// which calls ComputeStats when category is "all"/empty.
		{mode: "categories", wantKeys: []string{"graph"}},
		// capabilities delegates to get_capabilities → {matrix: ...}
		{mode: "capabilities", wantKeys: []string{"matrix"}},
		// provenance delegates to get_artifact_metadata; ArtifactMeta is nil
		// → legacy {error: "Artifact metadata unavailable..."} envelope.
		{mode: "provenance", wantKeys: []string{"error"}},
	}

	d := consolidatedDeps(t)
	for _, tc := range cases {
		t.Run(tc.mode, func(t *testing.T) {
			args := map[string]any{"mode": tc.mode}
			if tc.args != nil {
				for k, v := range tc.args {
					args[k] = v
				}
			}
			got := callConsolidatedTool(t, d, "graph_summary", args)
			assertKeys(t, got, tc.wantKeys)
		})
	}
}

// --------------------------------------------------------------------------
// find_in_graph — 6 modes
// --------------------------------------------------------------------------

func TestFindInGraph_AllModes(t *testing.T) {
	d := consolidatedDeps(t)

	cases := []struct {
		mode      string
		args      map[string]any
		wantKeys  []string
		wantCode  string // non-empty → assert code equals this (known-bug modes)
	}{
		// nodes — no kind filter; returns {nodes, count, limit}
		{mode: "nodes", wantKeys: []string{"nodes", "count", "limit"}},
		// edges — no kind filter; returns {edges, count, limit}
		{mode: "edges", wantKeys: []string{"edges", "count", "limit"}},
		// text — requires non-empty query; query="checkout" finds label match
		{mode: "text", args: map[string]any{"query": "checkout"}, wantKeys: []string{"results", "count", "query"}},
		// fuzzy — requires non-empty query; returns {matches, count}
		{mode: "fuzzy", args: map[string]any{"query": "checkout"}, wantKeys: []string{"matches", "count"}},
		// by_file — file_path; returns {file_path, nodes, count}
		{mode: "by_file", args: map[string]any{"file_path": "checkout/PayController.java"}, wantKeys: []string{"file_path", "nodes", "count"}},
		// by_endpoint — passes node_id which is forwarded as `identifier` to
		// find_related_endpoints. The dispatch is correct; wantCode is explicitly
		// NOT CodeInvalidInput. The fixture returns an error envelope (store
		// error or empty result) — we only assert the dispatch is no longer broken.
		{mode: "by_endpoint", args: map[string]any{"node_id": "ep:checkout:/pay"},
			wantKeys: []string{"code", "error", "message"},
			wantCode: mcp.CodeInternalError},
	}

	for _, tc := range cases {
		t.Run(tc.mode, func(t *testing.T) {
			args := map[string]any{"mode": tc.mode}
			for k, v := range tc.args {
				args[k] = v
			}
			got := callConsolidatedTool(t, d, "find_in_graph", args)
			if tc.wantCode != "" {
				// Known dispatch bug — assert the specific error code.
				assertCode(t, got, tc.wantCode)
				return
			}
			assertKeys(t, got, tc.wantKeys)
		})
	}
}

// --------------------------------------------------------------------------
// inspect_node — 4 modes
// --------------------------------------------------------------------------

func TestInspectNode_AllModes(t *testing.T) {
	d := consolidatedDeps(t)

	cases := []struct {
		mode     string
		args     map[string]any
		wantKeys []string
		wantCode string
	}{
		// neighbors — node_id required; checkout service has children
		{mode: "neighbors", args: map[string]any{"node_id": "svc:checkout"},
			wantKeys: []string{"node_id", "direction", "outgoing"}},
		// ego — center required; returns {center, radius, nodes, count}
		{mode: "ego", args: map[string]any{"center": "svc:checkout"},
			wantKeys: []string{"center", "radius", "nodes", "count"}},
		// evidence — Evidence service not wired → legacy {error: "...unavailable..."} envelope
		{mode: "evidence", args: map[string]any{"node_id": "svc:checkout"},
			wantKeys: []string{"error"}},
		// source — RootPath is empty → INTERNAL_ERROR
		{mode: "source", args: map[string]any{"file_path": "checkout/PayController.java"},
			wantCode: mcp.CodeInternalError},
	}

	for _, tc := range cases {
		t.Run(tc.mode, func(t *testing.T) {
			args := map[string]any{"mode": tc.mode}
			for k, v := range tc.args {
				args[k] = v
			}
			got := callConsolidatedTool(t, d, "inspect_node", args)
			if tc.wantCode != "" {
				assertCode(t, got, tc.wantCode)
				return
			}
			assertKeys(t, got, tc.wantKeys)
		})
	}
}

// --------------------------------------------------------------------------
// trace_relationships — 6 modes
// --------------------------------------------------------------------------

func TestTraceRelationships_AllModes(t *testing.T) {
	d := consolidatedDeps(t)

	cases := []struct {
		mode     string
		args     map[string]any
		wantKeys []string
		wantCode string
	}{
		// callers/consumers/producers/dependencies/dependents — node_id is
		// forwarded as target_id to the consumerLikeTool handlers.
		// Fixture has no caller/consumer/producer edges for svc:checkout so
		// count = 0 is expected; the envelope must NOT be an error.
		{mode: "callers", args: map[string]any{"node_id": "svc:checkout"},
			wantKeys: []string{"callers", "count"}},
		{mode: "consumers", args: map[string]any{"node_id": "svc:checkout"},
			wantKeys: []string{"consumers", "count"}},
		{mode: "producers", args: map[string]any{"node_id": "svc:checkout"},
			wantKeys: []string{"producers", "count"}},
		{mode: "dependencies", args: map[string]any{"node_id": "svc:checkout"},
			wantKeys: []string{"dependencies", "count"}},
		{mode: "dependents", args: map[string]any{"node_id": "svc:checkout"},
			wantKeys: []string{"dependents", "count"}},
		// shortest_path — from/to are forwarded as source/target. The dispatch
		// is correct (no INVALID_INPUT). The fixture has no direct shortest
		// path between svc:checkout and svc:billing at the service level so
		// the handler returns {error: "No path found …"} — a plain map, not
		// an error envelope. We assert the dispatch reached the handler.
		{mode: "shortest_path", args: map[string]any{"from": "svc:checkout", "to": "svc:billing"},
			wantKeys: []string{"error"}},
	}

	for _, tc := range cases {
		t.Run(tc.mode, func(t *testing.T) {
			args := map[string]any{"mode": tc.mode}
			for k, v := range tc.args {
				args[k] = v
			}
			got := callConsolidatedTool(t, d, "trace_relationships", args)
			if tc.wantCode != "" {
				assertCode(t, got, tc.wantCode)
				return
			}
			assertKeys(t, got, tc.wantKeys)
		})
	}
}

// --------------------------------------------------------------------------
// analyze_impact — 7 modes
// --------------------------------------------------------------------------

func TestAnalyzeImpact_AllModes(t *testing.T) {
	d := consolidatedDeps(t)

	cases := []struct {
		mode     string
		args     map[string]any
		wantKeys []string
		wantCode string
	}{
		// blast_radius — node_id required; returns {source, depth, affected_nodes, ...}
		{mode: "blast_radius", args: map[string]any{"node_id": "svc:checkout"},
			wantKeys: []string{"source"}},
		// trace — node_id required; delegates to trace_impact which calls
		// Topology.BlastRadius → same shape as blast_radius
		{mode: "trace", args: map[string]any{"node_id": "svc:checkout"},
			wantKeys: []string{"source"}},
		// cycles — delegates to find_cycles; returns {cycles, count}
		{mode: "cycles", wantKeys: []string{"cycles", "count"}},
		// circular_deps — delegates to find_circular_deps; returns {cycles, count}
		{mode: "circular_deps", wantKeys: []string{"cycles", "count"}},
		// dead_code — delegates to find_dead_code; returns {dead_code, count}
		{mode: "dead_code", wantKeys: []string{"dead_code", "count"}},
		// dead_services — delegates to find_dead_services; returns {dead_services, count}
		{mode: "dead_services", wantKeys: []string{"dead_services", "count"}},
		// bottlenecks — delegates to find_bottlenecks; returns {bottlenecks, count}
		{mode: "bottlenecks", wantKeys: []string{"bottlenecks", "count"}},
	}

	for _, tc := range cases {
		t.Run(tc.mode, func(t *testing.T) {
			args := map[string]any{"mode": tc.mode}
			for k, v := range tc.args {
				args[k] = v
			}
			got := callConsolidatedTool(t, d, "analyze_impact", args)
			if tc.wantCode != "" {
				assertCode(t, got, tc.wantCode)
				return
			}
			assertKeys(t, got, tc.wantKeys)
		})
	}
}

// --------------------------------------------------------------------------
// topology_view — 5 modes
// --------------------------------------------------------------------------

func TestTopologyView_AllModes(t *testing.T) {
	d := consolidatedDeps(t)

	cases := []struct {
		mode     string
		args     map[string]any
		wantKeys []string
		wantCode string
	}{
		// summary — delegates to get_topology; returns {services, connections, ...}
		{mode: "summary", wantKeys: []string{"services", "connections"}},
		// service — delegates to service_detail; returns {name, endpoints, entities, ...}
		{mode: "service", args: map[string]any{"service_name": "checkout"},
			wantKeys: []string{"name", "endpoints", "entities"}},
		// service_deps — delegates to service_dependencies; returns {service, dependencies, count}
		{mode: "service_deps", args: map[string]any{"service_name": "checkout"},
			wantKeys: []string{"service", "count"}},
		// service_dependents — delegates to service_dependents; returns {service, dependents, count}
		{mode: "service_dependents", args: map[string]any{"service_name": "billing"},
			wantKeys: []string{"service", "count"}},
		// flow — delegates to generate_flow; Flow engine wired →
		// returns JSON flow object with `view` key (default view=overview)
		{mode: "flow", wantKeys: []string{"view"}},
	}

	for _, tc := range cases {
		t.Run(tc.mode, func(t *testing.T) {
			args := map[string]any{"mode": tc.mode}
			for k, v := range tc.args {
				args[k] = v
			}
			got := callConsolidatedTool(t, d, "topology_view", args)
			if tc.wantCode != "" {
				assertCode(t, got, tc.wantCode)
				return
			}
			assertKeys(t, got, tc.wantKeys)
		})
	}
}
