package linker

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/model"
)

func TestTopicLinkerStampsSource(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "svc:p", Kind: model.NodeService, Label: "p"},
		{ID: "svc:c", Kind: model.NodeService, Label: "c"},
		{ID: "topic:t", Kind: model.NodeTopic, Label: "t"},
	}
	edges := []*model.CodeEdge{
		{ID: "p->t", Kind: model.EdgeProduces, SourceID: "svc:p", TargetID: "topic:t"},
		{ID: "c->t", Kind: model.EdgeConsumes, SourceID: "svc:c", TargetID: "topic:t"},
	}
	r := NewTopicLinker().Link(nodes, edges)
	if len(r.Edges) == 0 {
		t.Fatal("TopicLinker emitted no edges")
	}
	for _, e := range r.Edges {
		if e.Source != SrcTopicLinker {
			t.Errorf("edge %s: Source = %q, want %q", e.ID, e.Source, SrcTopicLinker)
		}
	}
}

func TestEntityLinkerStampsSource(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "ent:User", Kind: model.NodeEntity, Label: "User"},
		{ID: "repo:UserRepo", Kind: model.NodeRepository, Label: "UserRepository"},
	}
	r := NewEntityLinker().Link(nodes, nil)
	if len(r.Edges) == 0 {
		t.Fatal("EntityLinker emitted no edges")
	}
	for _, e := range r.Edges {
		if e.Source != SrcEntityLinker {
			t.Errorf("edge %s: Source = %q, want %q", e.ID, e.Source, SrcEntityLinker)
		}
	}
}

func TestModuleContainmentLinkerStampsSource(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "n1", Kind: model.NodeClass, Label: "A", Module: "auth"},
		{ID: "n2", Kind: model.NodeClass, Label: "B", Module: "auth"},
	}
	r := NewModuleContainmentLinker().Link(nodes, nil)
	if len(r.Edges) == 0 {
		t.Fatal("ModuleContainmentLinker emitted no edges")
	}
	for _, e := range r.Edges {
		if e.Source != SrcModuleContainmentLinker {
			t.Errorf("edge %s: Source = %q, want %q", e.ID, e.Source, SrcModuleContainmentLinker)
		}
	}
	for _, n := range r.Nodes {
		if n.Source != SrcModuleContainmentLinker {
			t.Errorf("node %s: Source = %q, want %q", n.ID, n.Source, SrcModuleContainmentLinker)
		}
	}
}

func TestAllSourcesIncludesEveryLinker(t *testing.T) {
	want := map[string]bool{
		SrcTopicLinker:             false,
		SrcEntityLinker:            false,
		SrcModuleContainmentLinker: false,
	}
	for _, s := range AllSources {
		if _, ok := want[s]; !ok {
			t.Errorf("AllSources contains unknown tag %q", s)
			continue
		}
		want[s] = true
	}
	for tag, found := range want {
		if !found {
			t.Errorf("AllSources missing %q", tag)
		}
	}
}
