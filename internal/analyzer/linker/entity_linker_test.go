package linker_test

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/analyzer/linker"
	"github.com/randomcodespace/codeiq/internal/model"
)

func TestEntityLinkerMatchesUserRepositoryToUser(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "entity:User", Kind: model.NodeEntity, Label: "User"},
		{ID: "repo:UserRepository", Kind: model.NodeRepository, Label: "UserRepository"},
	}
	r := linker.NewEntityLinker().Link(nodes, nil)
	if len(r.Edges) != 1 {
		t.Fatalf("want 1 edge, got %d", len(r.Edges))
	}
	got := r.Edges[0]
	if got.Kind != model.EdgeQueries {
		t.Fatalf("want QUERIES kind, got %s", got.Kind)
	}
	if got.SourceID != "repo:UserRepository" || got.TargetID != "entity:User" {
		t.Fatalf("bad source/target: %s -> %s", got.SourceID, got.TargetID)
	}
	if got.ID != "entity-link:repo:UserRepository->entity:User" {
		t.Fatalf("bad id: %q", got.ID)
	}
	if got.Properties["inferred"] != true {
		t.Fatalf("missing inferred=true")
	}
}

func TestEntityLinkerSupportsAllSuffixVariants(t *testing.T) {
	cases := []struct {
		repoLabel string
		entityID  string
	}{
		{"OrderRepository", "entity:Order"},
		{"ItemRepo", "entity:Item"},
		{"ProductDao", "entity:Product"},
		{"CustomerDAO", "entity:Customer"},
	}
	nodes := []*model.CodeNode{
		{ID: "entity:Order", Kind: model.NodeEntity, Label: "Order"},
		{ID: "entity:Item", Kind: model.NodeEntity, Label: "Item"},
		{ID: "entity:Product", Kind: model.NodeEntity, Label: "Product"},
		{ID: "entity:Customer", Kind: model.NodeEntity, Label: "Customer"},
	}
	for _, c := range cases {
		repo := &model.CodeNode{ID: "repo:" + c.repoLabel, Kind: model.NodeRepository, Label: c.repoLabel}
		all := append([]*model.CodeNode{}, nodes...)
		all = append(all, repo)
		r := linker.NewEntityLinker().Link(all, nil)
		if len(r.Edges) != 1 {
			t.Fatalf("suffix %q: want 1 edge, got %d", c.repoLabel, len(r.Edges))
		}
		if r.Edges[0].TargetID != c.entityID {
			t.Fatalf("suffix %q: want target %s, got %s", c.repoLabel, c.entityID, r.Edges[0].TargetID)
		}
	}
}

func TestEntityLinkerSkipsWhenQueriesEdgeAlreadyExists(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "entity:User", Kind: model.NodeEntity, Label: "User"},
		{ID: "repo:UserRepository", Kind: model.NodeRepository, Label: "UserRepository"},
	}
	edges := []*model.CodeEdge{
		{ID: "existing", Kind: model.EdgeQueries, SourceID: "repo:UserRepository", TargetID: "entity:User"},
	}
	r := linker.NewEntityLinker().Link(nodes, edges)
	if len(r.Edges) != 0 {
		t.Fatalf("want 0 edges (existing QUERIES suppresses), got %d", len(r.Edges))
	}
}

func TestEntityLinkerSkipsUnrecognisedSuffix(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "entity:User", Kind: model.NodeEntity, Label: "User"},
		{ID: "svc:UserService", Kind: model.NodeRepository, Label: "UserService"},
	}
	r := linker.NewEntityLinker().Link(nodes, nil)
	if len(r.Edges) != 0 {
		t.Fatalf("want 0 edges (no recognised suffix), got %d", len(r.Edges))
	}
}

func TestEntityLinkerSkipsWhenEntityMissing(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "repo:UserRepository", Kind: model.NodeRepository, Label: "UserRepository"},
	}
	r := linker.NewEntityLinker().Link(nodes, nil)
	if len(r.Edges) != 0 {
		t.Fatalf("want 0 edges (no entity), got %d", len(r.Edges))
	}
}

func TestEntityLinkerCaseInsensitiveMatch(t *testing.T) {
	// Repository label suffix is stripped, then lower-cased; entity is keyed
	// by lower-cased label. So `userrepository` strips → `user` → matches
	// `User`.
	nodes := []*model.CodeNode{
		{ID: "entity:User", Kind: model.NodeEntity, Label: "User"},
		{ID: "repo:userRepository", Kind: model.NodeRepository, Label: "userRepository"},
	}
	r := linker.NewEntityLinker().Link(nodes, nil)
	if len(r.Edges) != 1 {
		t.Fatalf("want 1 edge (case-insensitive), got %d", len(r.Edges))
	}
}

func TestEntityLinkerMatchesByFQNSimpleName(t *testing.T) {
	// Entity has FQN; repository label matches the simple name from the FQN.
	nodes := []*model.CodeNode{
		{ID: "entity:com.acme.User", Kind: model.NodeEntity, Label: "User", FQN: "com.acme.User"},
		{ID: "repo:UserRepository", Kind: model.NodeRepository, Label: "UserRepository"},
	}
	r := linker.NewEntityLinker().Link(nodes, nil)
	if len(r.Edges) != 1 {
		t.Fatalf("want 1 edge (FQN simple-name match), got %d", len(r.Edges))
	}
}

func TestEntityLinkerOnlyFirstSuffixWins(t *testing.T) {
	// "UserRepo" — `Repo` matches before `Dao`/`DAO`. Make sure we don't
	// emit duplicate edges by also trying later suffixes.
	nodes := []*model.CodeNode{
		{ID: "entity:User", Kind: model.NodeEntity, Label: "User"},
		{ID: "repo:UserRepo", Kind: model.NodeRepository, Label: "UserRepo"},
	}
	r := linker.NewEntityLinker().Link(nodes, nil)
	if len(r.Edges) != 1 {
		t.Fatalf("want exactly 1 edge (first suffix wins), got %d", len(r.Edges))
	}
}
