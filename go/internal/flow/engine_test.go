package flow_test

import (
	"context"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/flow"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// makeSnapshot builds an in-memory snapshot for unit testing the engine
// without round-tripping through Kuzu.
func makeSnapshot() *flow.Snapshot {
	nodes := []*model.CodeNode{
		{ID: "endpoint:GET:/users", Kind: model.NodeEndpoint, Label: "GET /users", Properties: map[string]any{}},
		{ID: "endpoint:POST:/users", Kind: model.NodeEndpoint, Label: "POST /users", Properties: map[string]any{}},
		{ID: "entity:User", Kind: model.NodeEntity, Label: "User", Properties: map[string]any{}},
		{ID: "guard:JwtGuard", Kind: model.NodeGuard, Label: "JwtGuard", Properties: map[string]any{"auth_type": "jwt"}},
		{ID: "k8s:Deployment/api", Kind: model.NodeInfraResource, Label: "api", Properties: map[string]any{}},
		{ID: "tf:aws_lambda", Kind: model.NodeInfraResource, Label: "aws_lambda", Properties: map[string]any{}},
		{ID: "compose:db", Kind: model.NodeInfraResource, Label: "db", Properties: map[string]any{}},
		{ID: "gha:.github/workflows/ci.yml", Kind: model.NodeModule, Label: "ci.yml", Properties: map[string]any{}},
		{ID: "gha:ci.yml:job:build", Kind: model.NodeMethod, Label: "build", Module: "gha:.github/workflows/ci.yml", Properties: map[string]any{}},
	}
	edges := []*model.CodeEdge{
		{Kind: model.EdgeProtects, SourceID: "guard:JwtGuard", TargetID: "endpoint:GET:/users", Properties: map[string]any{}},
	}
	return flow.NewSnapshot(nodes, edges)
}

// TestEngineGenerateOverviewHasSubgraphs asserts the overview view
// produces non-empty subgraphs over the canned snapshot.
func TestEngineGenerateOverviewHasSubgraphs(t *testing.T) {
	snap := makeSnapshot()
	eng := flow.NewEngineFromSnapshot(snap)
	d, err := eng.Generate(context.Background(), flow.ViewOverview)
	if err != nil {
		t.Fatalf("Generate overview: %v", err)
	}
	if d.Title != "Architecture Overview" {
		t.Errorf("Title = %q, want Architecture Overview", d.Title)
	}
	if d.View != "overview" {
		t.Errorf("View = %q, want overview", d.View)
	}
	if len(d.Subgraphs) == 0 {
		t.Errorf("expected non-empty subgraphs, got %d", len(d.Subgraphs))
	}
	// CI subgraph must be present given the gha: nodes.
	if findSG(d, "ci") == nil {
		t.Errorf("expected ci subgraph, got %v", subgraphIDs(d))
	}
	// Infra subgraph must be present given the k8s:/tf:/compose: nodes.
	if findSG(d, "infra") == nil {
		t.Errorf("expected infra subgraph, got %v", subgraphIDs(d))
	}
	// App subgraph must be present given the endpoint/entity nodes.
	if findSG(d, "app") == nil {
		t.Errorf("expected app subgraph, got %v", subgraphIDs(d))
	}
	// Security subgraph must be present given the guard node.
	if findSG(d, "security") == nil {
		t.Errorf("expected security subgraph, got %v", subgraphIDs(d))
	}
}

// TestEngineGenerateAllReturnsFiveDiagrams asserts GenerateAll returns one
// diagram per supported view.
func TestEngineGenerateAllReturnsFiveDiagrams(t *testing.T) {
	snap := makeSnapshot()
	eng := flow.NewEngineFromSnapshot(snap)
	all, err := eng.GenerateAll(context.Background())
	if err != nil {
		t.Fatalf("GenerateAll: %v", err)
	}
	for _, v := range flow.AllViews() {
		d, ok := all[v]
		if !ok {
			t.Errorf("missing diagram for view %q", v)
			continue
		}
		if d.View != string(v) {
			t.Errorf("view %q diagram.View = %q", v, d.View)
		}
	}
}

// TestEngineGenerateRejectsUnknownView asserts an error is returned for an
// unsupported view name — the CLI relies on this for input validation.
func TestEngineGenerateRejectsUnknownView(t *testing.T) {
	snap := makeSnapshot()
	eng := flow.NewEngineFromSnapshot(snap)
	if _, err := eng.Generate(context.Background(), flow.View("bogus")); err == nil {
		t.Fatal("expected error for unknown view")
	}
}

// TestAuthViewCoverage asserts the coverage_pct stat is exactly 50.0 when
// one of two endpoints is protected. Pins the math.Round behaviour.
func TestAuthViewCoverage(t *testing.T) {
	snap := makeSnapshot()
	eng := flow.NewEngineFromSnapshot(snap)
	d, err := eng.Generate(context.Background(), flow.ViewAuth)
	if err != nil {
		t.Fatalf("Generate auth: %v", err)
	}
	cov, _ := d.Stats["coverage_pct"].(float64)
	if cov != 50.0 {
		t.Errorf("coverage_pct = %v, want 50.0", cov)
	}
}

// --- helpers ---

func findSG(d *flow.Diagram, id string) *flow.Subgraph {
	for i := range d.Subgraphs {
		if d.Subgraphs[i].ID == id {
			return &d.Subgraphs[i]
		}
	}
	return nil
}

func subgraphIDs(d *flow.Diagram) []string {
	out := make([]string, len(d.Subgraphs))
	for i, sg := range d.Subgraphs {
		out[i] = sg.ID
	}
	return out
}
