package linker_test

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/analyzer/linker"
	"github.com/randomcodespace/codeiq/internal/model"
)

func TestModuleContainmentLinkerCreatesModuleNodeAndContainsEdges(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "class:A", Kind: model.NodeClass, Label: "A", Module: "com.acme.core"},
		{ID: "class:B", Kind: model.NodeClass, Label: "B", Module: "com.acme.core"},
	}
	r := linker.NewModuleContainmentLinker().Link(nodes, nil)
	if len(r.Nodes) != 1 {
		t.Fatalf("want 1 new module node, got %d", len(r.Nodes))
	}
	mod := r.Nodes[0]
	if mod.ID != "module:com.acme.core" || mod.Kind != model.NodeModule {
		t.Fatalf("bad module node: %+v", mod)
	}
	if mod.Label != "com.acme.core" || mod.FQN != "com.acme.core" || mod.Module != "com.acme.core" {
		t.Fatalf("module name fields not set: label=%q fqn=%q module=%q", mod.Label, mod.FQN, mod.Module)
	}
	if len(r.Edges) != 2 {
		t.Fatalf("want 2 CONTAINS edges, got %d", len(r.Edges))
	}
	for _, e := range r.Edges {
		if e.Kind != model.EdgeContains {
			t.Fatalf("want CONTAINS, got %s", e.Kind)
		}
		if e.SourceID != "module:com.acme.core" {
			t.Fatalf("bad source: %s", e.SourceID)
		}
		if e.Properties["inferred"] != true {
			t.Fatalf("missing inferred=true")
		}
	}
}

func TestModuleContainmentLinkerReusesExistingModuleNode(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "module:com.acme.core", Kind: model.NodeModule, Label: "com.acme.core"},
		{ID: "class:A", Kind: model.NodeClass, Label: "A", Module: "com.acme.core"},
	}
	r := linker.NewModuleContainmentLinker().Link(nodes, nil)
	if len(r.Nodes) != 0 {
		t.Fatalf("want 0 new module nodes (existing reused), got %d", len(r.Nodes))
	}
	if len(r.Edges) != 1 {
		t.Fatalf("want 1 CONTAINS edge, got %d", len(r.Edges))
	}
	if r.Edges[0].SourceID != "module:com.acme.core" || r.Edges[0].TargetID != "class:A" {
		t.Fatalf("bad edge: %+v", r.Edges[0])
	}
}

func TestModuleContainmentLinkerSkipsExistingContainsEdge(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "module:com.acme.core", Kind: model.NodeModule, Label: "com.acme.core"},
		{ID: "class:A", Kind: model.NodeClass, Label: "A", Module: "com.acme.core"},
	}
	edges := []*model.CodeEdge{
		{ID: "pre", Kind: model.EdgeContains, SourceID: "module:com.acme.core", TargetID: "class:A"},
	}
	r := linker.NewModuleContainmentLinker().Link(nodes, edges)
	if len(r.Edges) != 0 {
		t.Fatalf("want 0 new edges (duplicate suppressed), got %d", len(r.Edges))
	}
}

func TestModuleContainmentLinkerSkipsNodesWithEmptyModule(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "class:A", Kind: model.NodeClass, Label: "A"},
		{ID: "class:B", Kind: model.NodeClass, Label: "B", Module: ""},
	}
	r := linker.NewModuleContainmentLinker().Link(nodes, nil)
	if len(r.Nodes) != 0 || len(r.Edges) != 0 {
		t.Fatalf("want empty result for nodes with empty module, got %d nodes, %d edges", len(r.Nodes), len(r.Edges))
	}
}

func TestModuleContainmentLinkerSkipsModuleKindNodesWithSelfModule(t *testing.T) {
	// MODULE-kind nodes are excluded from membership grouping even if their
	// own Module field is set — they can't contain themselves.
	nodes := []*model.CodeNode{
		{ID: "module:com.acme.core", Kind: model.NodeModule, Label: "com.acme.core", Module: "com.acme.core"},
	}
	r := linker.NewModuleContainmentLinker().Link(nodes, nil)
	if len(r.Nodes) != 0 || len(r.Edges) != 0 {
		t.Fatalf("want empty result; module shouldn't contain itself, got %d nodes, %d edges", len(r.Nodes), len(r.Edges))
	}
}

func TestModuleContainmentLinkerDeterministic(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "class:Z", Kind: model.NodeClass, Label: "Z", Module: "mod.b"},
		{ID: "class:A", Kind: model.NodeClass, Label: "A", Module: "mod.a"},
		{ID: "class:M", Kind: model.NodeClass, Label: "M", Module: "mod.a"},
		{ID: "class:N", Kind: model.NodeClass, Label: "N", Module: "mod.b"},
	}
	var firstNodeIDs, firstEdgeIDs []string
	for i := 0; i < 5; i++ {
		r := linker.NewModuleContainmentLinker().Link(nodes, nil)

		nIDs := make([]string, 0, len(r.Nodes))
		for _, n := range r.Nodes {
			nIDs = append(nIDs, n.ID)
		}
		sort.Strings(nIDs)

		eIDs := make([]string, 0, len(r.Edges))
		for _, e := range r.Edges {
			eIDs = append(eIDs, e.ID)
		}
		sort.Strings(eIDs)

		if firstNodeIDs == nil {
			firstNodeIDs = nIDs
			firstEdgeIDs = eIDs
			continue
		}
		if len(firstNodeIDs) != len(nIDs) || len(firstEdgeIDs) != len(eIDs) {
			t.Fatalf("non-deterministic count")
		}
		for j := range nIDs {
			if firstNodeIDs[j] != nIDs[j] {
				t.Fatalf("non-deterministic node ids")
			}
		}
		for j := range eIDs {
			if firstEdgeIDs[j] != eIDs[j] {
				t.Fatalf("non-deterministic edge ids")
			}
		}
	}
}

func TestModuleContainmentLinkerEmitsEdgesInModuleThenMemberOrder(t *testing.T) {
	// Spec from the plan: emit CONTAINS edges sorted by module then by
	// member ID. So `mod.a` members (sorted) come before `mod.b` members.
	nodes := []*model.CodeNode{
		{ID: "class:b_member", Kind: model.NodeClass, Label: "b_member", Module: "mod.b"},
		{ID: "class:a_member", Kind: model.NodeClass, Label: "a_member", Module: "mod.a"},
		{ID: "class:a_member2", Kind: model.NodeClass, Label: "a_member2", Module: "mod.a"},
	}
	r := linker.NewModuleContainmentLinker().Link(nodes, nil)
	if len(r.Edges) != 3 {
		t.Fatalf("want 3 edges, got %d", len(r.Edges))
	}
	wantOrder := []string{
		"module-link:module:mod.a->class:a_member",
		"module-link:module:mod.a->class:a_member2",
		"module-link:module:mod.b->class:b_member",
	}
	for i, e := range r.Edges {
		if e.ID != wantOrder[i] {
			t.Fatalf("edge[%d]: want %q, got %q", i, wantOrder[i], e.ID)
		}
	}
}
