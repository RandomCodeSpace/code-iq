package analyzer

import (
	"sort"
	"sync"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// GraphBuilder buffers detector results across batches. Concurrent-safe.
// Snapshot() produces a deterministic sorted view with dangling edges
// dropped — the same determinism contract as the Java GraphBuilder.
type GraphBuilder struct {
	mu    sync.Mutex
	nodes map[string]*model.CodeNode
	edges map[string]*model.CodeEdge
}

// NewGraphBuilder returns an empty builder.
func NewGraphBuilder() *GraphBuilder {
	return &GraphBuilder{
		nodes: make(map[string]*model.CodeNode),
		edges: make(map[string]*model.CodeEdge),
	}
}

// Add merges a detector result. Duplicate node IDs are dropped (first write
// wins — matches Java behaviour). Duplicate edge IDs likewise.
func (b *GraphBuilder) Add(r *detector.Result) {
	if r == nil {
		return
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	for _, n := range r.Nodes {
		if _, exists := b.nodes[n.ID]; !exists {
			b.nodes[n.ID] = n
		}
	}
	for _, e := range r.Edges {
		if _, exists := b.edges[e.ID]; !exists {
			b.edges[e.ID] = e
		}
	}
}

// Snapshot is the deterministic, sorted view of buffered state with dangling
// edges (source or target node missing) dropped.
type Snapshot struct {
	Nodes []*model.CodeNode
	Edges []*model.CodeEdge
}

// Snapshot returns the current state as a sorted, dangling-edge-free Snapshot.
func (b *GraphBuilder) Snapshot() Snapshot {
	b.mu.Lock()
	defer b.mu.Unlock()
	nodes := make([]*model.CodeNode, 0, len(b.nodes))
	for _, n := range b.nodes {
		nodes = append(nodes, n)
	}
	sort.Slice(nodes, func(i, j int) bool { return nodes[i].ID < nodes[j].ID })

	edges := make([]*model.CodeEdge, 0, len(b.edges))
	for _, e := range b.edges {
		if _, src := b.nodes[e.SourceID]; !src {
			continue
		}
		if _, tgt := b.nodes[e.TargetID]; !tgt {
			continue
		}
		edges = append(edges, e)
	}
	sort.Slice(edges, func(i, j int) bool { return edges[i].ID < edges[j].ID })

	return Snapshot{Nodes: nodes, Edges: edges}
}
