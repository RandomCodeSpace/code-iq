package query_test

import (
	"path/filepath"
	"reflect"
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/graph"
	"github.com/randomcodespace/codeiq/go/internal/model"
	"github.com/randomcodespace/codeiq/go/internal/query"
)

// topologyFixture mirrors TopologyService.java's test shape. Two SERVICE
// nodes (checkout, billing) plus child ENDPOINT / ENTITY / GUARD / DB /
// TOPIC nodes connected via the standard CONTAINS structural edges. A
// single cross-service CALLS edge from checkout's endpoint to billing's
// entity drives the connection / blast / bottleneck / circular tests.
func topologyFixture(t *testing.T) (*graph.Store, *query.Topology) {
	t.Helper()
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.Close() })
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}

	checkout := &model.CodeNode{ID: "svc:checkout", Kind: model.NodeService, Label: "checkout",
		Layer: model.LayerBackend,
		Properties: map[string]any{
			"build_tool":     "maven",
			"endpoint_count": int64(1),
			"entity_count":   int64(1),
		}}
	billing := &model.CodeNode{ID: "svc:billing", Kind: model.NodeService, Label: "billing",
		Layer: model.LayerBackend,
		Properties: map[string]any{
			"build_tool":     "maven",
			"endpoint_count": int64(0),
			"entity_count":   int64(1),
		}}
	// Child nodes — each tags `service` property + structural CONTAINS edge.
	ep := &model.CodeNode{ID: "ep:checkout:/pay", Kind: model.NodeEndpoint, Label: "POST /pay",
		FilePath: "checkout/PayController.java", Layer: model.LayerBackend,
		Properties: map[string]any{"service": "checkout", "http_method": "POST"}}
	chOrder := &model.CodeNode{ID: "entity:checkout:Order", Kind: model.NodeEntity, Label: "Order",
		FilePath: "checkout/Order.java", Layer: model.LayerBackend,
		Properties: map[string]any{"service": "checkout"}}
	guard := &model.CodeNode{ID: "guard:checkout:JwtFilter", Kind: model.NodeGuard, Label: "JwtFilter",
		FilePath: "checkout/JwtFilter.java", Layer: model.LayerBackend,
		Properties: map[string]any{"service": "checkout", "auth_type": "jwt"}}
	dbConn := &model.CodeNode{ID: "db:checkout:primary", Kind: model.NodeDatabaseConnection, Label: "primary",
		FilePath: "checkout/application.yml", Layer: model.LayerInfra,
		Properties: map[string]any{"service": "checkout", "db_type": "postgres"}}
	topic := &model.CodeNode{ID: "topic:checkout:created", Kind: model.NodeTopic, Label: "checkout.created",
		FilePath: "checkout/EventConfig.java", Layer: model.LayerInfra,
		Properties: map[string]any{"service": "checkout", "protocol": "kafka"}}
	// Billing's entity — target of cross-service CALLS from checkout.
	blInvoice := &model.CodeNode{ID: "entity:billing:Invoice", Kind: model.NodeEntity, Label: "Invoice",
		FilePath: "billing/Invoice.java", Layer: model.LayerBackend,
		Properties: map[string]any{"service": "billing"}}

	nodes := []*model.CodeNode{checkout, billing, ep, chOrder, guard, dbConn, topic, blInvoice}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatal(err)
	}

	edges := []*model.CodeEdge{
		// Structural CONTAINS edges (service → child) so the queries can
		// pivot through the graph rather than parsing JSON props.
		{ID: "e1", Kind: model.EdgeContains, SourceID: "svc:checkout", TargetID: "ep:checkout:/pay"},
		{ID: "e2", Kind: model.EdgeContains, SourceID: "svc:checkout", TargetID: "entity:checkout:Order"},
		{ID: "e3", Kind: model.EdgeContains, SourceID: "svc:checkout", TargetID: "guard:checkout:JwtFilter"},
		{ID: "e4", Kind: model.EdgeContains, SourceID: "svc:checkout", TargetID: "db:checkout:primary"},
		{ID: "e5", Kind: model.EdgeContains, SourceID: "svc:checkout", TargetID: "topic:checkout:created"},
		{ID: "e6", Kind: model.EdgeContains, SourceID: "svc:billing", TargetID: "entity:billing:Invoice"},
		// Cross-service runtime CALLS edge: checkout's endpoint calls billing's entity.
		{ID: "e7", Kind: model.EdgeCalls, SourceID: "ep:checkout:/pay", TargetID: "entity:billing:Invoice"},
	}
	if err := s.BulkLoadEdges(edges); err != nil {
		t.Fatal(err)
	}
	return s, query.NewTopology(s)
}

func TestGetTopologyReturnsServices(t *testing.T) {
	_, top := topologyFixture(t)
	out, err := top.GetTopology()
	if err != nil {
		t.Fatal(err)
	}
	services, ok := out.Values["services"].([]map[string]any)
	if !ok {
		t.Fatalf("services not []map[string]any: %T", out.Values["services"])
	}
	if len(services) != 2 {
		t.Fatalf("want 2 services, got %d", len(services))
	}
	// Sorted ascending by name → billing, checkout.
	names := []string{services[0]["name"].(string), services[1]["name"].(string)}
	if want := []string{"billing", "checkout"}; !reflect.DeepEqual(names, want) {
		t.Fatalf("service order want %v, got %v", want, names)
	}

	// connections_in / connections_out wired off CALLS.
	for _, svc := range services {
		switch svc["name"].(string) {
		case "checkout":
			if svc["connections_out"].(int64) != 1 {
				t.Fatalf("checkout connections_out want 1, got %v", svc["connections_out"])
			}
		case "billing":
			if svc["connections_in"].(int64) != 1 {
				t.Fatalf("billing connections_in want 1, got %v", svc["connections_in"])
			}
		}
	}

	conns, ok := out.Values["connections"].([]map[string]any)
	if !ok {
		t.Fatalf("connections not []map[string]any: %T", out.Values["connections"])
	}
	if len(conns) != 1 {
		t.Fatalf("want 1 connection, got %d", len(conns))
	}
	c := conns[0]
	if c["source"] != "checkout" || c["target"] != "billing" || c["type"] != "calls" {
		t.Fatalf("connection wrong: %+v", c)
	}
}

func TestServiceDetailCheckout(t *testing.T) {
	_, top := topologyFixture(t)
	d, err := top.ServiceDetail("checkout")
	if err != nil {
		t.Fatal(err)
	}
	endpoints := d.Values["endpoints"].([]map[string]any)
	if len(endpoints) != 1 || endpoints[0]["id"] != "ep:checkout:/pay" {
		t.Fatalf("endpoints want one /pay, got %+v", endpoints)
	}
	entities := d.Values["entities"].([]map[string]any)
	if len(entities) != 1 || entities[0]["id"] != "entity:checkout:Order" {
		t.Fatalf("entities wrong: %+v", entities)
	}
	guards := d.Values["guards"].([]map[string]any)
	if len(guards) != 1 {
		t.Fatalf("guards want 1, got %d", len(guards))
	}
	dbs := d.Values["databases"].([]map[string]any)
	if len(dbs) != 1 {
		t.Fatalf("dbs want 1, got %d", len(dbs))
	}
	queues := d.Values["queues"].([]map[string]any)
	if len(queues) != 1 {
		t.Fatalf("queues want 1, got %d", len(queues))
	}
}

func TestBlastRadiusFromEndpoint(t *testing.T) {
	_, top := topologyFixture(t)
	out, err := top.BlastRadius("ep:checkout:/pay", 2)
	if err != nil {
		t.Fatal(err)
	}
	affected, ok := out.Values["affected_nodes"].([]map[string]any)
	if !ok {
		t.Fatalf("affected_nodes not []map[string]any: %T", out.Values["affected_nodes"])
	}
	ids := make([]string, len(affected))
	for i, a := range affected {
		ids[i] = a["id"].(string)
	}
	sort.Strings(ids)
	// Only one downstream reachable via runtime CALLS edge.
	if want := []string{"entity:billing:Invoice"}; !reflect.DeepEqual(ids, want) {
		t.Fatalf("want %v, got %v", want, ids)
	}
}

func TestFindBottlenecks(t *testing.T) {
	_, top := topologyFixture(t)
	rows, err := top.FindBottlenecks()
	if err != nil {
		t.Fatal(err)
	}
	if len(rows) == 0 {
		t.Fatalf("want at least one bottleneck service, got none")
	}
	// Both checkout (1 out) and billing (1 in) participate.
	got := map[string]struct {
		in, out int64
	}{}
	for _, r := range rows {
		svc := r["service"].(string)
		got[svc] = struct {
			in, out int64
		}{r["connections_in"].(int64), r["connections_out"].(int64)}
	}
	if got["checkout"].out != 1 {
		t.Fatalf("checkout out want 1, got %d", got["checkout"].out)
	}
	if got["billing"].in != 1 {
		t.Fatalf("billing in want 1, got %d", got["billing"].in)
	}
}

func TestFindCircularEmptyOnTopologyFixture(t *testing.T) {
	// The default fixture is a checkout → billing DAG with no cycle.
	_, top := topologyFixture(t)
	cycles, err := top.FindCircular()
	if err != nil {
		t.Fatal(err)
	}
	if len(cycles) != 0 {
		t.Fatalf("want no service cycles, got %v", cycles)
	}
}

func TestFindCircularDetectsServiceCycle(t *testing.T) {
	// Augment the fixture with a billing→checkout edge to create a
	// service-level A↔B cycle. New store keeps the test isolated.
	s, err := graph.Open(filepath.Join(t.TempDir(), "g.kuzu"))
	if err != nil {
		t.Fatal(err)
	}
	defer s.Close()
	if err := s.ApplySchema(); err != nil {
		t.Fatal(err)
	}
	nodes := []*model.CodeNode{
		{ID: "svc:a", Kind: model.NodeService, Label: "a", Layer: model.LayerBackend},
		{ID: "svc:b", Kind: model.NodeService, Label: "b", Layer: model.LayerBackend},
		{ID: "ep:a:x", Kind: model.NodeEndpoint, Label: "x", Layer: model.LayerBackend,
			Properties: map[string]any{"service": "a"}},
		{ID: "ep:b:y", Kind: model.NodeEndpoint, Label: "y", Layer: model.LayerBackend,
			Properties: map[string]any{"service": "b"}},
	}
	if err := s.BulkLoadNodes(nodes); err != nil {
		t.Fatal(err)
	}
	edges := []*model.CodeEdge{
		{ID: "c1", Kind: model.EdgeContains, SourceID: "svc:a", TargetID: "ep:a:x"},
		{ID: "c2", Kind: model.EdgeContains, SourceID: "svc:b", TargetID: "ep:b:y"},
		{ID: "x1", Kind: model.EdgeCalls, SourceID: "ep:a:x", TargetID: "ep:b:y"},
		{ID: "x2", Kind: model.EdgeCalls, SourceID: "ep:b:y", TargetID: "ep:a:x"},
	}
	if err := s.BulkLoadEdges(edges); err != nil {
		t.Fatal(err)
	}
	top := query.NewTopology(s)
	cycles, err := top.FindCircular()
	if err != nil {
		t.Fatal(err)
	}
	if len(cycles) == 0 {
		t.Fatalf("want at least one service cycle, got none")
	}
	// First cycle starts and ends with the same service name.
	c := cycles[0]
	if len(c) < 3 || c[0] != c[len(c)-1] {
		t.Fatalf("cycle malformed: %v", c)
	}
}

func TestFindDeadServices(t *testing.T) {
	// In topologyFixture, billing has incoming (checkout→billing); checkout
	// does not. checkout is therefore a "dead service" by the algorithm
	// (no incoming runtime edges from other services).
	_, top := topologyFixture(t)
	rows, err := top.FindDeadServices()
	if err != nil {
		t.Fatal(err)
	}
	names := make([]string, len(rows))
	for i, r := range rows {
		names[i] = r["service"].(string)
	}
	sort.Strings(names)
	if want := []string{"checkout"}; !reflect.DeepEqual(names, want) {
		t.Fatalf("want %v, got %v", want, names)
	}
}

func TestFindPathSimple(t *testing.T) {
	_, top := topologyFixture(t)
	path, err := top.FindPath("checkout", "billing")
	if err != nil {
		t.Fatal(err)
	}
	if len(path) != 1 {
		t.Fatalf("want 1 hop, got %d (%v)", len(path), path)
	}
	hop := path[0]
	if hop["from"] != "checkout" || hop["to"] != "billing" || hop["type"] != "calls" {
		t.Fatalf("hop wrong: %+v", hop)
	}
}
