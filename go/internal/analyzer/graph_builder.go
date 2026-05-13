package analyzer

import (
	"sort"
	"sync"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// GraphBuilder buffers detector results across batches. Concurrent-safe.
//
// Phase 1 (plan §1.1, §1.2):
//   - Nodes are deduped by ID via mergeNode (confidence-aware).
//   - Edges are deduped by canonical (source, target, kind) key via mergeEdge.
//
// Snapshot() produces a deterministic sorted view with phantom edges (those
// whose endpoint is still missing) dropped, and exposes the dedup/drop
// counts so the CLI can surface "deduped N, dropped K" diagnostics.
type GraphBuilder struct {
	mu    sync.Mutex
	nodes map[string]*model.CodeNode
	edges map[edgeKey]*model.CodeEdge

	// Counters incremented as Add() observes duplicates and used by
	// Snapshot() to populate the surfaced stats.
	dedupedNodes int
	dedupedEdges int
}

// NewGraphBuilder returns an empty builder.
func NewGraphBuilder() *GraphBuilder {
	return &GraphBuilder{
		nodes: make(map[string]*model.CodeNode),
		edges: make(map[edgeKey]*model.CodeEdge),
	}
}

// Add merges a detector result. Duplicate node IDs and duplicate edge
// (source, target, kind) tuples collapse with confidence-aware merging.
func (b *GraphBuilder) Add(r *detector.Result) {
	if r == nil {
		return
	}
	b.mu.Lock()
	defer b.mu.Unlock()
	for _, n := range r.Nodes {
		if existing, ok := b.nodes[n.ID]; ok {
			b.nodes[n.ID] = mergeNode(existing, n)
			b.dedupedNodes++
			continue
		}
		b.nodes[n.ID] = n
	}
	for _, e := range r.Edges {
		k := makeEdgeKey(e)
		if existing, ok := b.edges[k]; ok {
			b.edges[k] = mergeEdge(existing, e)
			b.dedupedEdges++
			continue
		}
		b.edges[k] = e
	}
}

// Snapshot is the deterministic, sorted view of buffered state with
// phantom edges (source or target node missing) dropped. It also exposes
// the count of duplicate emissions collapsed during Add() and the count
// of dangling edges dropped during this Snapshot call.
type Snapshot struct {
	Nodes []*model.CodeNode
	Edges []*model.CodeEdge

	// DedupedNodes is the count of node emissions that collided with an
	// existing node ID and were merged in. Zero on a graph where no
	// detector double-emitted.
	DedupedNodes int
	// DedupedEdges is the same for edges by (source, target, kind).
	DedupedEdges int
	// DroppedEdges is the count of edges that had no matching source or
	// target node in the final node set — phantom references usually
	// caused by a linker pointing at a node that no detector emitted.
	DroppedEdges int
}

// Snapshot returns the current state as a sorted, dangling-edge-free
// Snapshot with surfaced dedup/drop counts.
//
// After this call returns, the builder's internal dedup maps are cleared
// (set to nil). This releases ~280 MB of reference pressure at ~/projects/
// scale where the downstream enrich pipeline holds the returned Snapshot
// slices for the lifetime of the function — coexisting with the dedup
// maps was the largest in-memory duplication in the pipeline. Snapshot
// is therefore single-shot: subsequent calls to Snapshot or Add on the
// same builder are not supported.
func (b *GraphBuilder) Snapshot() Snapshot {
	b.mu.Lock()
	defer b.mu.Unlock()
	nodes := make([]*model.CodeNode, 0, len(b.nodes))
	for _, n := range b.nodes {
		nodes = append(nodes, n)
	}
	sort.Slice(nodes, func(i, j int) bool { return nodes[i].ID < nodes[j].ID })

	edges := make([]*model.CodeEdge, 0, len(b.edges))
	dropped := 0
	for _, e := range b.edges {
		if _, src := b.nodes[e.SourceID]; !src {
			dropped++
			continue
		}
		if _, tgt := b.nodes[e.TargetID]; !tgt {
			dropped++
			continue
		}
		edges = append(edges, e)
	}
	sort.Slice(edges, func(i, j int) bool { return edges[i].ID < edges[j].ID })

	snap := Snapshot{
		Nodes:        nodes,
		Edges:        edges,
		DedupedNodes: b.dedupedNodes,
		DedupedEdges: b.dedupedEdges,
		DroppedEdges: dropped,
	}
	// Release dedup maps so Go GC can collect them while downstream
	// enrich stages run. The maps held references to every node and
	// edge already projected into the returned slices.
	b.nodes = nil
	b.edges = nil
	return snap
}
