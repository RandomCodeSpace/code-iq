package mcp

import (
	"github.com/randomcodespace/codeiq/go/internal/graph"
	"github.com/randomcodespace/codeiq/go/internal/query"
)

// Deps is the bundle of services every tool handler receives. Keep it
// small — adding fields here is a sign a tool wants to reach across
// layers. Prefer narrowing the interface in the tool registration site.
//
// Today (phase 3 partial — graph tools only) Deps carries the graph
// store, the read services, and the hot-path caps loaded from
// codeiq.yml. Evidence-pack assembler / flow engine / query planner
// get wired in as later phases land their tools (find_node /
// generate_flow / get_evidence_pack).
type Deps struct {
	// Store is the read-only Kuzu handle opened by `codeiq mcp` at
	// server boot.
	Store *graph.Store

	// Query owns the high-level read service (consumers / producers /
	// callers / dependencies / shortest path / cycles / dead code).
	// Mirrors Java QueryService.
	Query *query.Service

	// Stats owns the StoreStatsService façade (rich categorized stats).
	Stats *query.StoreStatsService

	// Topology owns the service-topology projection (Topology /
	// ServiceDetail / BlastRadius / FindPath / Bottlenecks / Circular
	// / DeadServices).
	Topology *query.Topology

	// RootPath is the absolute repo root the read_file tool resolves
	// caller paths against. Empty disables the read_file tool.
	RootPath string

	// MaxResults caps caller-supplied result counts (e.g. limit=1000
	// hits CapResults(limit, MaxResults)). Defaults are filled in via
	// CapResults if MaxResults <= 0.
	MaxResults int

	// MaxDepth caps caller-supplied traversal depths (e.g.
	// trace_impact / ego_graph). Defaults via CapDepth.
	MaxDepth int
}
