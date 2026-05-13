package linker_test

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/analyzer/linker"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestTopicLinkerPairsProducerToConsumer(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "topic:orders", Kind: model.NodeTopic, Label: "orders"},
		{ID: "svc:checkout", Kind: model.NodeService, Label: "checkout"},
		{ID: "svc:fulfilment", Kind: model.NodeService, Label: "fulfilment"},
	}
	edges := []*model.CodeEdge{
		{ID: "p1", Kind: model.EdgeProduces, SourceID: "svc:checkout", TargetID: "topic:orders"},
		{ID: "c1", Kind: model.EdgeConsumes, SourceID: "svc:fulfilment", TargetID: "topic:orders"},
	}
	r := linker.NewTopicLinker().Link(nodes, edges)
	if len(r.Edges) != 1 {
		t.Fatalf("want 1 edge, got %d", len(r.Edges))
	}
	got := r.Edges[0]
	if got.SourceID != "svc:checkout" || got.TargetID != "svc:fulfilment" || got.Kind != model.EdgeCalls {
		t.Fatalf("bad edge: %+v", got)
	}
	if got.ID != "topic-link:svc:checkout->svc:fulfilment" {
		t.Fatalf("bad id: %q", got.ID)
	}
	if got.Properties["inferred"] != true {
		t.Fatalf("missing inferred=true")
	}
	if got.Properties["topic"] != "orders" {
		t.Fatalf("missing topic=orders, got %v", got.Properties["topic"])
	}
}

func TestTopicLinkerDeterministic(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "topic:t1", Kind: model.NodeTopic, Label: "t1"},
		{ID: "p1", Kind: model.NodeService, Label: "p1"},
		{ID: "c1", Kind: model.NodeService, Label: "c1"},
		{ID: "c2", Kind: model.NodeService, Label: "c2"},
	}
	edges := []*model.CodeEdge{
		{ID: "e1", Kind: model.EdgeProduces, SourceID: "p1", TargetID: "topic:t1"},
		{ID: "e2", Kind: model.EdgeConsumes, SourceID: "c1", TargetID: "topic:t1"},
		{ID: "e3", Kind: model.EdgeConsumes, SourceID: "c2", TargetID: "topic:t1"},
	}
	var firstIDs []string
	for i := 0; i < 5; i++ {
		r := linker.NewTopicLinker().Link(nodes, edges)
		ids := make([]string, 0, len(r.Edges))
		for _, e := range r.Edges {
			ids = append(ids, e.ID)
		}
		sort.Strings(ids)
		if firstIDs == nil {
			firstIDs = ids
		} else if len(firstIDs) != len(ids) {
			t.Fatalf("non-deterministic count")
		} else {
			for j := range ids {
				if firstIDs[j] != ids[j] {
					t.Fatalf("non-deterministic ids")
				}
			}
		}
	}
}

func TestTopicLinkerSupportsAllProducerConsumerKinds(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "topic:q1", Kind: model.NodeQueue, Label: "q1"},
		{ID: "topic:e1", Kind: model.NodeEvent, Label: "e1"},
		{ID: "topic:m1", Kind: model.NodeMessageQueue, Label: "m1"},
		{ID: "p1", Kind: model.NodeService, Label: "p1"},
		{ID: "p2", Kind: model.NodeService, Label: "p2"},
		{ID: "p3", Kind: model.NodeService, Label: "p3"},
		{ID: "c1", Kind: model.NodeService, Label: "c1"},
		{ID: "c2", Kind: model.NodeService, Label: "c2"},
		{ID: "c3", Kind: model.NodeService, Label: "c3"},
	}
	edges := []*model.CodeEdge{
		{ID: "e1", Kind: model.EdgeSendsTo, SourceID: "p1", TargetID: "topic:q1"},
		{ID: "e2", Kind: model.EdgeReceivesFrom, SourceID: "c1", TargetID: "topic:q1"},
		{ID: "e3", Kind: model.EdgePublishes, SourceID: "p2", TargetID: "topic:e1"},
		{ID: "e4", Kind: model.EdgeListens, SourceID: "c2", TargetID: "topic:e1"},
		{ID: "e5", Kind: model.EdgeProduces, SourceID: "p3", TargetID: "topic:m1"},
		{ID: "e6", Kind: model.EdgeConsumes, SourceID: "c3", TargetID: "topic:m1"},
	}
	r := linker.NewTopicLinker().Link(nodes, edges)
	if len(r.Edges) != 3 {
		t.Fatalf("want 3 edges (one per topic), got %d", len(r.Edges))
	}
}

func TestTopicLinkerSkipsSelfLoops(t *testing.T) {
	nodes := []*model.CodeNode{
		{ID: "topic:t1", Kind: model.NodeTopic, Label: "t1"},
		{ID: "svc:a", Kind: model.NodeService, Label: "a"},
	}
	edges := []*model.CodeEdge{
		{ID: "p", Kind: model.EdgeProduces, SourceID: "svc:a", TargetID: "topic:t1"},
		{ID: "c", Kind: model.EdgeConsumes, SourceID: "svc:a", TargetID: "topic:t1"},
	}
	r := linker.NewTopicLinker().Link(nodes, edges)
	if len(r.Edges) != 0 {
		t.Fatalf("want 0 edges (self-loop suppressed), got %d", len(r.Edges))
	}
}

func TestTopicLinkerNoTopicsReturnsEmpty(t *testing.T) {
	nodes := []*model.CodeNode{{ID: "svc:a", Kind: model.NodeService, Label: "a"}}
	r := linker.NewTopicLinker().Link(nodes, nil)
	if len(r.Edges) != 0 || len(r.Nodes) != 0 {
		t.Fatalf("expected empty result")
	}
}

func TestTopicLinkerMergesTopicsBySharedLabel(t *testing.T) {
	// Two topic nodes with the same label (e.g. defined in two files) should
	// be merged: producer on one node, consumer on the other, must still link.
	nodes := []*model.CodeNode{
		{ID: "topic:a:orders", Kind: model.NodeTopic, Label: "orders"},
		{ID: "topic:b:orders", Kind: model.NodeTopic, Label: "orders"},
		{ID: "svc:p", Kind: model.NodeService, Label: "p"},
		{ID: "svc:c", Kind: model.NodeService, Label: "c"},
	}
	edges := []*model.CodeEdge{
		{ID: "p", Kind: model.EdgeProduces, SourceID: "svc:p", TargetID: "topic:a:orders"},
		{ID: "c", Kind: model.EdgeConsumes, SourceID: "svc:c", TargetID: "topic:b:orders"},
	}
	r := linker.NewTopicLinker().Link(nodes, edges)
	if len(r.Edges) != 1 {
		t.Fatalf("want 1 edge after label merge, got %d", len(r.Edges))
	}
}
