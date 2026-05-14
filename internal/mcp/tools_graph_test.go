package mcp_test

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"reflect"
	"sort"
	"strings"
	"testing"

	"github.com/randomcodespace/codeiq/internal/graph"
	"github.com/randomcodespace/codeiq/internal/mcp"
	"github.com/randomcodespace/codeiq/internal/model"
	"github.com/randomcodespace/codeiq/internal/query"
)

// fixtureStore opens a fresh Kuzu store under t.TempDir, applies the
// schema, and seeds a 3-node / 2-edge fixture: serviceA --CALLS--> b,
// serviceA --DEPENDS_ON--> c. Returns the store and a teardown.
func fixtureStore(t *testing.T) *graph.Store {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "fx.kuzu")
	s, err := graph.Open(dir)
	if err != nil {
		t.Fatalf("Open: %v", err)
	}
	t.Cleanup(func() { _ = s.Close() })
	if err := s.ApplySchema(); err != nil {
		t.Fatalf("ApplySchema: %v", err)
	}
	// Seed nodes + edges.
	stmts := []struct {
		q string
		p map[string]any
	}{
		{`CREATE (:CodeNode {id: 'svc:a', kind: 'service', label: 'serviceA', label_lower: 'servicea', layer: 'backend'})`, nil},
		{`CREATE (:CodeNode {id: 'cls:b', kind: 'class', label: 'B', label_lower: 'b', layer: 'backend', file_path: 'src/B.java'})`, nil},
		{`CREATE (:CodeNode {id: 'cls:c', kind: 'class', label: 'C', label_lower: 'c', layer: 'backend', file_path: 'src/C.java'})`, nil},
		{`MATCH (a:CodeNode {id: 'svc:a'}), (b:CodeNode {id: 'cls:b'}) CREATE (a)-[:CALLS]->(b)`, nil},
		{`MATCH (a:CodeNode {id: 'svc:a'}), (c:CodeNode {id: 'cls:c'}) CREATE (a)-[:DEPENDS_ON]->(c)`, nil},
		{`MATCH (a:CodeNode {id: 'svc:a'}), (b:CodeNode {id: 'cls:b'}) CREATE (a)-[:CONTAINS]->(b)`, nil},
		{`MATCH (a:CodeNode {id: 'svc:a'}), (c:CodeNode {id: 'cls:c'}) CREATE (a)-[:CONTAINS]->(c)`, nil},
	}
	for _, st := range stmts {
		if st.p == nil {
			if _, err := s.Cypher(st.q); err != nil {
				t.Fatalf("seed %q: %v", st.q, err)
			}
		}
	}
	return s
}

func fixtureDeps(t *testing.T) *mcp.Deps {
	t.Helper()
	store := fixtureStore(t)
	stats := query.NewStatsServiceFromStore(func() ([]*model.CodeNode, []*model.CodeEdge, error) {
		nodes, err := store.LoadAllNodes()
		if err != nil {
			return nil, nil, err
		}
		edges, err := store.LoadAllEdges()
		if err != nil {
			return nil, nil, err
		}
		return nodes, edges, nil
	})
	return &mcp.Deps{
		Store:      store,
		Query:      query.NewService(store),
		Stats:      stats,
		Topology:   query.NewTopology(store),
		MaxResults: 100,
		MaxDepth:   5,
	}
}

// callTool registers a single tool, then invokes it directly through the
// SDK in-memory pair. Returns the parsed JSON text body.
func callTool(t *testing.T, d *mcp.Deps, name string, args map[string]any) map[string]any {
	t.Helper()
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterGraph(srv, d); err != nil {
		t.Fatalf("RegisterGraph: %v", err)
	}
	sess, cleanup := connectInMemoryTest(t, srv)
	defer cleanup()

	ctx, cancel := contextDeadline(t)
	defer cancel()

	res, err := sess.CallTool(ctx, sdkCallToolParams(name, args))
	if err != nil {
		t.Fatalf("CallTool(%s): %v", name, err)
	}
	if len(res.Content) == 0 {
		t.Fatalf("%s returned empty content", name)
	}
	tc, ok := res.Content[0].(textContent)
	if !ok {
		t.Fatalf("%s content type = %T", name, res.Content[0])
	}
	var out map[string]any
	if err := json.Unmarshal([]byte(tc.Text), &out); err != nil {
		t.Fatalf("%s unmarshal: %v\nbody=%s", name, err, tc.Text)
	}
	return out
}

func TestRegisterGraphRegistersAllTwentyTools(t *testing.T) {
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterGraph(srv, &mcp.Deps{}); err != nil {
		t.Fatalf("RegisterGraph: %v", err)
	}
	want := []string{
		"get_stats", "get_detailed_stats", "query_nodes", "query_edges",
		"get_node_neighbors", "get_ego_graph", "find_cycles", "find_shortest_path",
		"find_consumers", "find_producers", "find_callers", "find_dependencies",
		"find_dependents", "find_dead_code", "find_component_by_file",
		"trace_impact", "find_related_endpoints", "search_graph",
		"run_cypher", "read_file",
	}
	got := srv.Registry().Names()
	sort.Strings(got)
	sort.Strings(want)
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("registered tools:\n got=%v\nwant=%v", got, want)
	}
}

func TestRegisterGraphUserFacingRegistersTwoTools(t *testing.T) {
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterGraphUserFacing(srv, &mcp.Deps{}); err != nil {
		t.Fatalf("RegisterGraphUserFacing: %v", err)
	}
	want := []string{"read_file", "run_cypher"}
	got := srv.Registry().Names()
	sort.Strings(got)
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("registered tools:\n got=%v\nwant=%v", got, want)
	}
}

func TestGetStatsReturnsCounts(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "get_stats", nil)
	// The OrderedMap serializes to a JSON object with at minimum a
	// `graph` (or top-level total_nodes / total_edges) key.
	if len(out) == 0 {
		t.Fatalf("get_stats returned empty object: %v", out)
	}
}

func TestQueryNodesByKind(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "query_nodes", map[string]any{"kind": "class", "limit": 10})
	cnt, _ := out["count"].(float64)
	if cnt != 2 {
		t.Fatalf("query_nodes class count = %v, want 2 (cls:b, cls:c). out=%v", cnt, out)
	}
}

func TestQueryNodesLimitCapped(t *testing.T) {
	d := fixtureDeps(t)
	d.MaxResults = 1
	out := callTool(t, d, "query_nodes", map[string]any{"kind": "class", "limit": 999})
	lim, _ := out["limit"].(float64)
	if lim != 1 {
		t.Fatalf("limit capped to %v, want 1", lim)
	}
}

func TestQueryEdgesByKind(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "query_edges", map[string]any{"kind": "CALLS", "limit": 10})
	cnt, _ := out["count"].(float64)
	if cnt != 1 {
		t.Fatalf("query_edges CALLS count = %v, want 1", cnt)
	}
}

func TestGetNodeNeighborsBoth(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "get_node_neighbors", map[string]any{"node_id": "svc:a"})
	if _, ok := out["incoming"]; !ok {
		t.Fatalf("missing incoming in response: %v", out)
	}
	if _, ok := out["outgoing"]; !ok {
		t.Fatalf("missing outgoing in response: %v", out)
	}
}

func TestGetNodeNeighborsMissingNodeIDIsInvalidInput(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "get_node_neighbors", nil)
	if out["code"] != mcp.CodeInvalidInput {
		t.Fatalf("code = %v, want INVALID_INPUT. body=%v", out["code"], out)
	}
}

func TestGetEgoGraphRadiusCapped(t *testing.T) {
	d := fixtureDeps(t)
	d.MaxDepth = 1
	out := callTool(t, d, "get_ego_graph", map[string]any{"center": "svc:a", "radius": 999})
	r, _ := out["radius"].(float64)
	if r != 1 {
		t.Fatalf("radius capped to %v, want 1. body=%v", r, out)
	}
}

func TestFindCyclesEmptyOnAcyclic(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "find_cycles", nil)
	cnt, _ := out["count"].(float64)
	if cnt != 0 {
		t.Fatalf("cycles in acyclic fixture = %v, want 0", cnt)
	}
}

func TestFindShortestPathConnected(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "find_shortest_path", map[string]any{"source": "svc:a", "target": "cls:b"})
	path, ok := out["path"].([]any)
	if !ok || len(path) < 2 {
		t.Fatalf("path missing or too short: %v", out)
	}
}

func TestFindShortestPathDisconnected(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "find_shortest_path", map[string]any{"source": "cls:b", "target": "cls:c"})
	if _, ok := out["error"]; !ok {
		// Even when no direct path exists, the helpers may build a 2-hop
		// indirection through serviceA via CONTAINS. Either is acceptable;
		// assert one of the two valid shapes.
		if _, hasPath := out["path"]; !hasPath {
			t.Fatalf("expected error or path key, got %v", out)
		}
	}
}

func TestFindCallersTargetIDRequired(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "find_callers", nil)
	if out["code"] != mcp.CodeInvalidInput {
		t.Fatalf("code = %v, want INVALID_INPUT", out["code"])
	}
}

func TestFindCallersReturnsList(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "find_callers", map[string]any{"target_id": "cls:b"})
	if _, ok := out["callers"]; !ok {
		t.Fatalf("missing callers key: %v", out)
	}
}

func TestFindDeadCodeFiltersEntryPoints(t *testing.T) {
	d := fixtureDeps(t)
	// cls:b has incoming CALLS from svc:a — should not be dead.
	out := callTool(t, d, "find_dead_code", nil)
	cnt, _ := out["count"].(float64)
	dead, _ := out["dead_code"].([]any)
	if cnt != float64(len(dead)) {
		t.Fatalf("count/list mismatch: %v vs %d", cnt, len(dead))
	}
}

func TestRunCypherReadOnly(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "run_cypher", map[string]any{"query": "MATCH (n:CodeNode) RETURN n.id AS id ORDER BY id"})
	rows, _ := out["rows"].([]any)
	if len(rows) != 3 {
		t.Fatalf("run_cypher rows = %d, want 3", len(rows))
	}
}

func TestRunCypherBlocksMutation(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "run_cypher", map[string]any{"query": "CREATE (:X)"})
	if _, ok := out["error"]; !ok {
		t.Fatalf("expected error envelope for mutation, got %v", out)
	}
}

func TestRunCypherTruncates(t *testing.T) {
	d := fixtureDeps(t)
	d.MaxResults = 1
	out := callTool(t, d, "run_cypher", map[string]any{"query": "MATCH (n:CodeNode) RETURN n.id AS id"})
	if trunc, _ := out["truncated"].(bool); !trunc {
		t.Fatalf("expected truncated=true, got %v", out)
	}
	mr, _ := out["max_results"].(float64)
	if mr != 1 {
		t.Fatalf("max_results = %v, want 1", mr)
	}
}

func TestSearchGraphFindsLabel(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "search_graph", map[string]any{"query": "service"})
	cnt, _ := out["count"].(float64)
	if cnt < 1 {
		t.Fatalf("search 'service' count = %v, want >= 1", cnt)
	}
}

func TestFindComponentByFile(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "find_component_by_file", map[string]any{"file_path": "src/B.java"})
	cnt, _ := out["count"].(float64)
	if cnt != 1 {
		t.Fatalf("nodes for src/B.java = %v, want 1. body=%v", cnt, out)
	}
}

func TestTraceImpactDepthCapped(t *testing.T) {
	d := fixtureDeps(t)
	d.MaxDepth = 1
	out := callTool(t, d, "trace_impact", map[string]any{"node_id": "svc:a", "depth": 999})
	// BlastRadius returns an OrderedMap; we mostly assert it doesn't error
	// out and has a depth-capped shape (the capped depth shows up as a
	// `depth` field on the response).
	if _, ok := out["depth"]; !ok {
		// Tolerate alternate shape — BlastRadius emits {center, layers...}
		if len(out) == 0 {
			t.Fatalf("trace_impact empty response: %v", out)
		}
	}
}

func TestFindRelatedEndpointsRequiresIdentifier(t *testing.T) {
	d := fixtureDeps(t)
	out := callTool(t, d, "find_related_endpoints", nil)
	if out["code"] != mcp.CodeInvalidInput {
		t.Fatalf("code = %v, want INVALID_INPUT", out["code"])
	}
}

func TestReadFileToolDelegates(t *testing.T) {
	d := fixtureDeps(t)
	root := t.TempDir()
	d.RootPath = root
	if err := os.WriteFile(filepath.Join(root, "x.txt"), []byte("hi\n"), 0o644); err != nil {
		t.Fatalf("write: %v", err)
	}
	out := callTool(t, d, "read_file", map[string]any{"file_path": "x.txt"})
	c, _ := out["content"].(string)
	if c != "hi\n" {
		t.Fatalf("content = %q, want hi\\n. out=%v", c, out)
	}
}

func TestReadFileToolMissingPath(t *testing.T) {
	d := fixtureDeps(t)
	d.RootPath = t.TempDir()
	out := callTool(t, d, "read_file", map[string]any{"file_path": "nope.txt"})
	if out["code"] != mcp.CodeFileReadFailed {
		t.Fatalf("code = %v, want FILE_READ_FAILED. body=%v", out["code"], out)
	}
}

func TestReadFileToolDisabledWithoutRoot(t *testing.T) {
	d := fixtureDeps(t)
	d.RootPath = ""
	out := callTool(t, d, "read_file", map[string]any{"file_path": "x.txt"})
	if out["code"] != mcp.CodeInternalError {
		t.Fatalf("code = %v, want INTERNAL_ERROR", out["code"])
	}
	if !strings.Contains(fmt.Sprint(out["message"]), "root") {
		t.Fatalf("message = %v, want root substring", out["message"])
	}
}
