package flow

import (
	"context"
	"fmt"

	"github.com/randomcodespace/codeiq/go/internal/graph"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// Store is the minimum graph surface Engine needs. *graph.Store satisfies
// this; tests use an in-memory fake.
type Store interface {
	LoadAllNodes() ([]*model.CodeNode, error)
	LoadAllEdges() ([]*model.CodeEdge, error)
}

// Snapshot is a materialised view of the graph used by every view builder.
// Loading once at the top of Generate avoids the per-view round trip the
// Java `FlowDataSource.findByKind` pattern caused. Snapshot is read-only
// and safe to share across goroutines.
//
// Mirrors the Java side's CacheFlowDataSource — pre-loaded in memory so
// view builders are pure functions over (nodes, edges).
type Snapshot struct {
	Nodes []*model.CodeNode
	Edges []*model.CodeEdge

	// byKind indexes nodes by kind for FindByKind() lookups. Built lazily.
	byKind map[model.NodeKind][]*model.CodeNode
}

// NewSnapshot constructs a Snapshot over the supplied nodes and edges.
// Both slices are retained by reference — do not mutate after passing.
func NewSnapshot(nodes []*model.CodeNode, edges []*model.CodeEdge) *Snapshot {
	return &Snapshot{Nodes: nodes, Edges: edges}
}

// LoadSnapshot loads every node and edge from store. On large graphs this
// is materially heavier than the Java CacheFlowDataSource path; the
// flow command surface accepts the trade because it is interactive (one
// human call) rather than hot-path.
func LoadSnapshot(store Store) (*Snapshot, error) {
	nodes, err := store.LoadAllNodes()
	if err != nil {
		return nil, fmt.Errorf("flow: load nodes: %w", err)
	}
	edges, err := store.LoadAllEdges()
	if err != nil {
		return nil, fmt.Errorf("flow: load edges: %w", err)
	}
	return NewSnapshot(nodes, edges), nil
}

// FindByKind returns every node of the given kind. The result slice is a
// pointer back into Snapshot.Nodes — callers MUST NOT modify it.
func (s *Snapshot) FindByKind(kind model.NodeKind) []*model.CodeNode {
	if s.byKind == nil {
		s.byKind = make(map[model.NodeKind][]*model.CodeNode)
		for _, n := range s.Nodes {
			s.byKind[n.Kind] = append(s.byKind[n.Kind], n)
		}
	}
	return s.byKind[kind]
}

// Count returns the total node count. Mirrors Java FlowDataSource.count().
func (s *Snapshot) Count() int { return len(s.Nodes) }

// EdgesFrom returns edges whose source ID matches.
func (s *Snapshot) EdgesFrom(id string) []*model.CodeEdge {
	var out []*model.CodeEdge
	for _, e := range s.Edges {
		if e.SourceID == id {
			out = append(out, e)
		}
	}
	return out
}

// Engine is the flow-diagram generator. Stateless apart from the bound
// Store; safe for concurrent calls because Generate loads a fresh
// Snapshot every invocation.
//
// Mirrors src/main/java/.../flow/FlowEngine.java.
type Engine struct {
	store Store
}

// NewEngine constructs an Engine over the given Store.
func NewEngine(store Store) *Engine {
	return &Engine{store: store}
}

// NewEngineFromSnapshot constructs an Engine that returns the supplied
// snapshot from every call — useful for tests that want to avoid the
// Kuzu round trip.
func NewEngineFromSnapshot(snap *Snapshot) *Engine {
	return &Engine{store: snapshotStore{snap: snap}}
}

// Generate produces the Diagram for the named view. Returns an error
// when view is unknown.
func (e *Engine) Generate(ctx context.Context, view View) (*Diagram, error) {
	if !IsKnownView(string(view)) {
		return nil, fmt.Errorf("flow: unknown view %q (valid: overview, ci, deploy, runtime, auth)", view)
	}
	snap, err := LoadSnapshot(e.store)
	if err != nil {
		return nil, err
	}
	return e.generateFromSnapshot(view, snap), nil
}

// GenerateAll produces a Diagram for every supported view in declaration
// order. Loads the snapshot once and dispatches to every view builder.
func (e *Engine) GenerateAll(ctx context.Context) (map[View]*Diagram, error) {
	snap, err := LoadSnapshot(e.store)
	if err != nil {
		return nil, err
	}
	out := make(map[View]*Diagram, 5)
	for _, v := range AllViews() {
		out[v] = e.generateFromSnapshot(v, snap)
	}
	return out, nil
}

// generateFromSnapshot dispatches to the appropriate view builder.
func (e *Engine) generateFromSnapshot(view View, snap *Snapshot) *Diagram {
	switch view {
	case ViewOverview:
		return buildOverview(snap)
	case ViewCI:
		return buildCIView(snap)
	case ViewDeploy:
		return buildDeployView(snap)
	case ViewRuntime:
		return buildRuntimeView(snap)
	case ViewAuth:
		return buildAuthView(snap)
	}
	// Unreachable — Generate guards via IsKnownView.
	return NewDiagram("Unknown View", string(view))
}

// snapshotStore is the Store implementation that just hands back the
// pre-loaded snapshot. Used by NewEngineFromSnapshot.
type snapshotStore struct {
	snap *Snapshot
}

func (s snapshotStore) LoadAllNodes() ([]*model.CodeNode, error) { return s.snap.Nodes, nil }
func (s snapshotStore) LoadAllEdges() ([]*model.CodeEdge, error) { return s.snap.Edges, nil }

// Compile-time assertion that *graph.Store satisfies Store.
var _ Store = (*graph.Store)(nil)
