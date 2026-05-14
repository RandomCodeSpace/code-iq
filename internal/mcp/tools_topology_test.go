package mcp_test

import (
	"path/filepath"
	"reflect"
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/graph"
	"github.com/randomcodespace/codeiq/internal/mcp"
	"github.com/randomcodespace/codeiq/internal/model"
	"github.com/randomcodespace/codeiq/internal/query"
)

// topologyFixtureDeps mirrors the query.Topology test shape: two SERVICE
// nodes (checkout, billing), child endpoint/entity/guard/db/topic nodes
// wired via structural CONTAINS edges, and one cross-service CALLS edge
// (checkout's /pay endpoint → billing's Invoice entity). Returns *mcp.Deps
// with every read service wired.
func topologyFixtureDeps(t *testing.T) *mcp.Deps {
	t.Helper()
	s, err := graph.Open(filepath.Join(t.TempDir(), "topo.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = s.Close() })
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}

	checkout := &model.CodeNode{
		ID: "svc:checkout", Kind: model.NodeService, Label: "checkout",
		Layer: model.LayerBackend,
		Properties: map[string]any{
			"build_tool":     "maven",
			"endpoint_count": int64(1),
			"entity_count":   int64(1),
		},
	}
	billing := &model.CodeNode{
		ID: "svc:billing", Kind: model.NodeService, Label: "billing",
		Layer: model.LayerBackend,
		Properties: map[string]any{
			"build_tool":     "maven",
			"endpoint_count": int64(0),
			"entity_count":   int64(1),
		},
	}
	ep := &model.CodeNode{
		ID: "ep:checkout:/pay", Kind: model.NodeEndpoint, Label: "POST /pay",
		FilePath: "checkout/PayController.java", Layer: model.LayerBackend,
		Properties: map[string]any{"service": "checkout", "http_method": "POST"},
	}
	chOrder := &model.CodeNode{
		ID: "entity:checkout:Order", Kind: model.NodeEntity, Label: "Order",
		FilePath: "checkout/Order.java", Layer: model.LayerBackend,
		Properties: map[string]any{"service": "checkout"},
	}
	guard := &model.CodeNode{
		ID: "guard:checkout:JwtFilter", Kind: model.NodeGuard, Label: "JwtFilter",
		FilePath: "checkout/JwtFilter.java", Layer: model.LayerBackend,
		Properties: map[string]any{"service": "checkout", "auth_type": "jwt"},
	}
	dbConn := &model.CodeNode{
		ID: "db:checkout:primary", Kind: model.NodeDatabaseConnection, Label: "primary",
		FilePath: "checkout/application.yml", Layer: model.LayerInfra,
		Properties: map[string]any{"service": "checkout", "db_type": "postgres"},
	}
	topic := &model.CodeNode{
		ID: "topic:checkout:created", Kind: model.NodeTopic, Label: "checkout.created",
		FilePath: "checkout/EventConfig.java", Layer: model.LayerInfra,
		Properties: map[string]any{"service": "checkout", "protocol": "kafka"},
	}
	blInvoice := &model.CodeNode{
		ID: "entity:billing:Invoice", Kind: model.NodeEntity, Label: "Invoice",
		FilePath: "billing/Invoice.java", Layer: model.LayerBackend,
		Properties: map[string]any{"service": "billing"},
	}
	nodes := []*model.CodeNode{checkout, billing, ep, chOrder, guard, dbConn, topic, blInvoice}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatal(err)
	}
	edges := []*model.CodeEdge{
		{ID: "e1", Kind: model.EdgeContains, SourceID: "svc:checkout", TargetID: "ep:checkout:/pay"},
		{ID: "e2", Kind: model.EdgeContains, SourceID: "svc:checkout", TargetID: "entity:checkout:Order"},
		{ID: "e3", Kind: model.EdgeContains, SourceID: "svc:checkout", TargetID: "guard:checkout:JwtFilter"},
		{ID: "e4", Kind: model.EdgeContains, SourceID: "svc:checkout", TargetID: "db:checkout:primary"},
		{ID: "e5", Kind: model.EdgeContains, SourceID: "svc:checkout", TargetID: "topic:checkout:created"},
		{ID: "e6", Kind: model.EdgeContains, SourceID: "svc:billing", TargetID: "entity:billing:Invoice"},
		{ID: "e7", Kind: model.EdgeCalls, SourceID: "ep:checkout:/pay", TargetID: "entity:billing:Invoice"},
	}
	if err := s.BulkLoadEdges(edges); err != nil {
		t.Fatal(err)
	}

	stats := query.NewStatsServiceFromStore(func() ([]*model.CodeNode, []*model.CodeEdge, error) {
		ns, err := s.LoadAllNodes()
		if err != nil {
			return nil, nil, err
		}
		es, err := s.LoadAllEdges()
		if err != nil {
			return ns, nil, err
		}
		return ns, es, nil
	})
	return &mcp.Deps{
		Store:      s,
		Query:      query.NewService(s),
		Stats:      stats,
		Topology:   query.NewTopology(s),
		MaxResults: 100,
		MaxDepth:   5,
	}
}

// callTopologyTool wires a fresh server, registers all topology tools,
// invokes the named tool through the SDK in-memory transport, and
// returns the parsed result body.
func callTopologyTool(t *testing.T, d *mcp.Deps, name string, args map[string]any) map[string]any {
	t.Helper()
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterTopology(srv, d); err != nil {
		t.Fatalf("RegisterTopology: %v", err)
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
	return unmarshalJSON(t, tc.Text)
}

func TestRegisterTopologyRegistersNine(t *testing.T) {
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	if err := mcp.RegisterTopology(srv, &mcp.Deps{}); err != nil {
		t.Fatalf("RegisterTopology: %v", err)
	}
	want := []string{
		"get_topology", "service_detail", "service_dependencies",
		"service_dependents", "blast_radius", "find_path",
		"find_bottlenecks", "find_circular_deps", "find_dead_services",
	}
	got := srv.Registry().Names()
	sort.Strings(got)
	sort.Strings(want)
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("topology tools:\n got=%v\nwant=%v", got, want)
	}
}

func TestGetTopologyReturnsServicesAndConnections(t *testing.T) {
	d := topologyFixtureDeps(t)
	out := callTopologyTool(t, d, "get_topology", nil)
	if _, ok := out["services"]; !ok {
		t.Fatalf("missing services key: %v", out)
	}
	if _, ok := out["connections"]; !ok {
		t.Fatalf("missing connections key: %v", out)
	}
	svcCount, _ := out["service_count"].(float64)
	if svcCount != 2 {
		t.Fatalf("service_count = %v, want 2", svcCount)
	}
	connCount, _ := out["connection_count"].(float64)
	if connCount != 1 {
		t.Fatalf("connection_count = %v, want 1", connCount)
	}
}

func TestServiceDetailReturnsChildBuckets(t *testing.T) {
	d := topologyFixtureDeps(t)
	out := callTopologyTool(t, d, "service_detail", map[string]any{"service_name": "checkout"})
	if out["name"] != "checkout" {
		t.Fatalf("name = %v, want checkout. body=%v", out["name"], out)
	}
	for _, k := range []string{"endpoints", "entities", "guards", "databases", "queues"} {
		if _, ok := out[k]; !ok {
			t.Fatalf("missing %q key: %v", k, out)
		}
	}
}

func TestServiceDetailRequiresName(t *testing.T) {
	d := topologyFixtureDeps(t)
	out := callTopologyTool(t, d, "service_detail", nil)
	if out["code"] != mcp.CodeInvalidInput {
		t.Fatalf("code = %v, want INVALID_INPUT", out["code"])
	}
}

func TestServiceDependenciesReturnsOutbound(t *testing.T) {
	d := topologyFixtureDeps(t)
	out := callTopologyTool(t, d, "service_dependencies", map[string]any{"service_name": "checkout"})
	if out["service"] != "checkout" {
		t.Fatalf("service = %v, want checkout", out["service"])
	}
	// checkout → billing via the cross-service CALLS edge.
	cnt, _ := out["count"].(float64)
	if cnt != 1 {
		t.Fatalf("count = %v, want 1. body=%v", cnt, out)
	}
}

func TestServiceDependentsReturnsInbound(t *testing.T) {
	d := topologyFixtureDeps(t)
	out := callTopologyTool(t, d, "service_dependents", map[string]any{"service_name": "billing"})
	if out["service"] != "billing" {
		t.Fatalf("service = %v, want billing", out["service"])
	}
	cnt, _ := out["count"].(float64)
	if cnt != 1 {
		t.Fatalf("count = %v, want 1 (checkout depends on billing). body=%v", cnt, out)
	}
}

func TestBlastRadiusRequiresNodeID(t *testing.T) {
	d := topologyFixtureDeps(t)
	out := callTopologyTool(t, d, "blast_radius", nil)
	if out["code"] != mcp.CodeInvalidInput {
		t.Fatalf("code = %v, want INVALID_INPUT", out["code"])
	}
}

func TestBlastRadiusReturnsAffectedNodes(t *testing.T) {
	d := topologyFixtureDeps(t)
	out := callTopologyTool(t, d, "blast_radius", map[string]any{"node_id": "ep:checkout:/pay"})
	if out["source"] != "ep:checkout:/pay" {
		t.Fatalf("source = %v, want ep:checkout:/pay", out["source"])
	}
	if _, ok := out["affected_nodes"]; !ok {
		t.Fatalf("missing affected_nodes: %v", out)
	}
}

func TestFindPathBetweenServices(t *testing.T) {
	d := topologyFixtureDeps(t)
	out := callTopologyTool(t, d, "find_path", map[string]any{
		"source": "checkout",
		"target": "billing",
	})
	hops, ok := out["path"].([]any)
	if !ok || len(hops) < 1 {
		t.Fatalf("path missing/empty: %v", out)
	}
	// First hop is checkout → billing.
	first, _ := hops[0].(map[string]any)
	if first["from"] != "checkout" || first["to"] != "billing" {
		t.Fatalf("first hop = %v, want checkout→billing", first)
	}
}

func TestFindPathRequiresBothEndpoints(t *testing.T) {
	d := topologyFixtureDeps(t)
	out := callTopologyTool(t, d, "find_path", map[string]any{"source": "checkout"})
	if out["code"] != mcp.CodeInvalidInput {
		t.Fatalf("code = %v, want INVALID_INPUT", out["code"])
	}
}

func TestFindBottlenecksReturnsRows(t *testing.T) {
	d := topologyFixtureDeps(t)
	out := callTopologyTool(t, d, "find_bottlenecks", nil)
	if _, ok := out["bottlenecks"]; !ok {
		t.Fatalf("missing bottlenecks key: %v", out)
	}
}

func TestFindCircularDepsAcyclic(t *testing.T) {
	d := topologyFixtureDeps(t)
	out := callTopologyTool(t, d, "find_circular_deps", nil)
	cnt, _ := out["count"].(float64)
	if cnt != 0 {
		t.Fatalf("expected zero cycles in acyclic fixture, got %v. body=%v", cnt, out)
	}
}

func TestFindDeadServicesReturnsCheckout(t *testing.T) {
	d := topologyFixtureDeps(t)
	out := callTopologyTool(t, d, "find_dead_services", nil)
	// checkout has no inbound runtime edges; billing is reached from checkout.
	// So checkout is the dead service in this fixture.
	rows, _ := out["dead_services"].([]any)
	if len(rows) != 1 {
		t.Fatalf("dead_services count = %d, want 1. body=%v", len(rows), out)
	}
	first, _ := rows[0].(map[string]any)
	if first["service"] != "checkout" {
		t.Fatalf("first dead service = %v, want checkout", first)
	}
}
