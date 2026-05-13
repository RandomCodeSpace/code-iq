package query_test

import (
	"reflect"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/model"
	"github.com/randomcodespace/codeiq/go/internal/query"
)

// statsFixture builds the deterministic 10-node + 10-edge graph the Stats
// tests run against. Mirrors the shape exercised by Java StatsServiceTest
// without the @SpringBootTest fluff.
func statsFixture() ([]*model.CodeNode, []*model.CodeEdge) {
	nodes := []*model.CodeNode{
		// Java backend stack
		{ID: "class:1", Kind: model.NodeClass, Label: "UserService",
			FilePath: "src/main/java/com/x/UserService.java",
			Properties: map[string]any{"framework": "spring_boot", "language": "java"}},
		{ID: "ep:1", Kind: model.NodeEndpoint, Label: "GET /users",
			FilePath: "src/main/java/com/x/UserController.java",
			Properties: map[string]any{"framework": "spring_boot", "http_method": "GET", "language": "java"}},
		{ID: "ep:2", Kind: model.NodeEndpoint, Label: "POST /users",
			FilePath: "src/main/java/com/x/UserController.java",
			Properties: map[string]any{"framework": "spring_boot", "http_method": "POST", "language": "java"}},
		{ID: "entity:1", Kind: model.NodeEntity, Label: "User",
			FilePath: "src/main/java/com/x/User.java",
			Properties: map[string]any{"framework": "jpa", "language": "java"}},
		{ID: "repo:1", Kind: model.NodeRepository, Label: "UserRepository",
			FilePath: "src/main/java/com/x/UserRepository.java",
			Properties: map[string]any{"framework": "spring_data", "language": "java"}},

		// Messaging + DB infra
		{ID: "topic:1", Kind: model.NodeTopic, Label: "users.created",
			FilePath: "src/main/java/com/x/UserProducer.java",
			Properties: map[string]any{"protocol": "kafka", "language": "java"}},
		{ID: "db:1", Kind: model.NodeDatabaseConnection, Label: "primary",
			FilePath: "src/main/resources/application.yml",
			Properties: map[string]any{"db_type": "postgres", "language": "yaml"}},

		// Guard with auth_type
		{ID: "guard:1", Kind: model.NodeGuard, Label: "JwtFilter",
			FilePath: "src/main/java/com/x/JwtFilter.java",
			Properties: map[string]any{"auth_type": "jwt", "framework": "spring_security", "language": "java"}},

		// Azure resource
		{ID: "az:1", Kind: model.NodeAzureResource, Label: "storage",
			FilePath: "infra/main.bicep",
			Properties: map[string]any{"resource_type": "Microsoft.Storage/storageAccounts", "language": "bicep"}},

		// Interface (architecture)
		{ID: "iface:1", Kind: model.NodeInterface, Label: "UserService",
			FilePath: "src/main/java/com/x/UserServiceI.java",
			Properties: map[string]any{"language": "java"}},
	}

	edges := []*model.CodeEdge{
		{ID: "e1", Kind: model.EdgeCalls, SourceID: "ep:1", TargetID: "class:1"},
		{ID: "e2", Kind: model.EdgeCalls, SourceID: "ep:2", TargetID: "class:1"},
		{ID: "e3", Kind: model.EdgeQueries, SourceID: "repo:1", TargetID: "entity:1"},
		{ID: "e4", Kind: model.EdgeProduces, SourceID: "class:1", TargetID: "topic:1"},
		{ID: "e5", Kind: model.EdgePublishes, SourceID: "class:1", TargetID: "topic:1"},
		{ID: "e6", Kind: model.EdgeConsumes, SourceID: "class:1", TargetID: "topic:1"},
		{ID: "e7", Kind: model.EdgeListens, SourceID: "class:1", TargetID: "topic:1"},
		{ID: "e8", Kind: model.EdgeConnectsTo, SourceID: "repo:1", TargetID: "db:1"},
		{ID: "e9", Kind: model.EdgeImports, SourceID: "class:1", TargetID: "iface:1"},
		{ID: "e10", Kind: model.EdgeProtects, SourceID: "guard:1", TargetID: "ep:1"},
	}
	return nodes, edges
}

func TestComputeStatsTopLevelOrder(t *testing.T) {
	nodes, edges := statsFixture()
	s := &query.StatsService{}
	out := s.ComputeStats(nodes, edges)

	want := []string{"graph", "languages", "frameworks", "infra", "connections", "auth", "architecture"}
	if !reflect.DeepEqual(out.Keys, want) {
		t.Fatalf("top-level key order wrong\n want %v\n got  %v", want, out.Keys)
	}
}

func TestComputeStatsGraphCategory(t *testing.T) {
	nodes, edges := statsFixture()
	s := &query.StatsService{}
	out := s.ComputeStats(nodes, edges)

	g, ok := out.Values["graph"].(*query.OrderedMap)
	if !ok {
		t.Fatalf("graph not OrderedMap: %T", out.Values["graph"])
	}
	if got := g.Values["nodes"].(int); got != 10 {
		t.Fatalf("nodes want 10, got %d", got)
	}
	if got := g.Values["edges"].(int); got != 10 {
		t.Fatalf("edges want 10, got %d", got)
	}
	if got := g.Values["files"].(int); got != 9 {
		// 10 nodes but UserController.java is shared by ep:1+ep:2 → 9 distinct.
		t.Fatalf("files want 9, got %d", got)
	}
	byKind := g.Values["edges_by_kind"].(*query.OrderedMap)
	if byKind.Values["calls"].(int) != 2 {
		t.Fatalf("edges_by_kind calls want 2, got %v", byKind.Values["calls"])
	}
}

func TestComputeStatsLanguages(t *testing.T) {
	nodes, edges := statsFixture()
	s := &query.StatsService{}
	out := s.ComputeStats(nodes, edges)

	langs := out.Values["languages"].(*query.OrderedMap)
	// 8 java nodes from properties; yaml=1, bicep=1.
	if langs.Values["java"].(int) != 8 {
		t.Fatalf("java want 8, got %v", langs.Values["java"])
	}
	if langs.Values["yaml"].(int) != 1 {
		t.Fatalf("yaml want 1, got %v", langs.Values["yaml"])
	}
	// First key must be the largest count (sorted desc by value).
	if langs.Keys[0] != "java" {
		t.Fatalf("first lang want java, got %s", langs.Keys[0])
	}
}

func TestComputeStatsFrameworks(t *testing.T) {
	nodes, edges := statsFixture()
	s := &query.StatsService{}
	out := s.ComputeStats(nodes, edges)

	fw := out.Values["frameworks"].(*query.OrderedMap)
	// spring_boot=3 (UserService + ep1 + ep2), jpa=1, spring_data=1, spring_security=1
	if fw.Values["spring_boot"].(int) != 3 {
		t.Fatalf("spring_boot want 3, got %v", fw.Values["spring_boot"])
	}
	if fw.Keys[0] != "spring_boot" {
		t.Fatalf("first framework want spring_boot, got %s", fw.Keys[0])
	}
}

func TestComputeStatsInfra(t *testing.T) {
	nodes, edges := statsFixture()
	s := &query.StatsService{}
	out := s.ComputeStats(nodes, edges)

	infra := out.Values["infra"].(*query.OrderedMap)
	dbs := infra.Values["databases"].(*query.OrderedMap)
	if dbs.Values["PostgreSQL"].(int) != 1 {
		t.Fatalf("PostgreSQL want 1, got %v", dbs.Values["PostgreSQL"])
	}
	msg := infra.Values["messaging"].(*query.OrderedMap)
	if msg.Values["kafka"].(int) != 1 {
		t.Fatalf("kafka want 1, got %v", msg.Values["kafka"])
	}
	cloud := infra.Values["cloud"].(*query.OrderedMap)
	if cloud.Values["Microsoft.Storage/storageAccounts"].(int) != 1 {
		t.Fatalf("storage want 1, got %v", cloud.Values["Microsoft.Storage/storageAccounts"])
	}
}

func TestComputeStatsConnections(t *testing.T) {
	nodes, edges := statsFixture()
	s := &query.StatsService{}
	out := s.ComputeStats(nodes, edges)

	conn := out.Values["connections"].(*query.OrderedMap)
	rest := conn.Values["rest"].(*query.OrderedMap)
	if rest.Values["total"].(int64) != 2 {
		t.Fatalf("rest.total want 2, got %v", rest.Values["total"])
	}
	if conn.Values["producers"].(int64) != 2 {
		t.Fatalf("producers want 2, got %v", conn.Values["producers"])
	}
	if conn.Values["consumers"].(int64) != 2 {
		t.Fatalf("consumers want 2, got %v", conn.Values["consumers"])
	}
}

func TestComputeStatsAuth(t *testing.T) {
	nodes, _ := statsFixture()
	s := &query.StatsService{}
	auth := s.ComputeCategory(nodes, nil, "auth")
	if auth == nil {
		t.Fatal("auth nil")
	}
	if auth.Values["jwt"].(int) != 1 {
		t.Fatalf("jwt want 1, got %v", auth.Values["jwt"])
	}
}

func TestComputeStatsArchitecture(t *testing.T) {
	nodes, _ := statsFixture()
	s := &query.StatsService{}
	arch := s.ComputeCategory(nodes, nil, "architecture")
	if arch == nil {
		t.Fatal("architecture nil")
	}
	// 1 class, 1 interface — only non-zero counts surface.
	if arch.Values["classes"].(int) != 1 {
		t.Fatalf("classes want 1, got %v", arch.Values["classes"])
	}
	if arch.Values["interfaces"].(int) != 1 {
		t.Fatalf("interfaces want 1, got %v", arch.Values["interfaces"])
	}
}

func TestComputeCategoryMatchesComputeStatsGraph(t *testing.T) {
	nodes, edges := statsFixture()
	s := &query.StatsService{}
	full := s.ComputeStats(nodes, edges)
	cat := s.ComputeCategory(nodes, edges, "graph")
	if !reflect.DeepEqual(cat, full.Values["graph"]) {
		t.Fatalf("ComputeCategory(\"graph\") mismatch with ComputeStats[\"graph\"]")
	}
}

func TestComputeCategoryUnknownReturnsNil(t *testing.T) {
	nodes, edges := statsFixture()
	s := &query.StatsService{}
	if got := s.ComputeCategory(nodes, edges, "bogus"); got != nil {
		t.Fatalf("unknown category want nil, got %+v", got)
	}
}

func TestComputeStatsDeterminism(t *testing.T) {
	// Run twice on identical input; results must match byte-for-byte once
	// rendered. OrderedMap.Keys preserves insertion order so two reflect.DeepEqual
	// checks against fresh runs suffice.
	nodes, edges := statsFixture()
	s := &query.StatsService{}
	a := s.ComputeStats(nodes, edges)
	b := s.ComputeStats(nodes, edges)
	if !reflect.DeepEqual(a, b) {
		t.Fatalf("non-deterministic ComputeStats output")
	}
}
