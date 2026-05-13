package mcp

import (
	"github.com/randomcodespace/codeiq/go/internal/flow"
	"github.com/randomcodespace/codeiq/go/internal/graph"
	"github.com/randomcodespace/codeiq/go/internal/intelligence/evidence"
	iqquery "github.com/randomcodespace/codeiq/go/internal/intelligence/query"
	"github.com/randomcodespace/codeiq/go/internal/query"
)

// Deps is the bundle of services every tool handler receives. Keep it
// small — adding fields here is a sign a tool wants to reach across
// layers. Prefer narrowing the interface in the tool registration site.
//
// Phase 3 wired:
//   - Store + Query + Stats + Topology cover the 20 graph tools and 9
//     topology tools.
//   - Flow drives the generate_flow tool.
//   - Evidence + QueryPlanner + ArtifactMeta drive the four intelligence
//     tools (find_node, get_evidence_pack, get_artifact_metadata,
//     get_capabilities).
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

	// Flow owns the architecture-flow-diagram engine. Wired by `codeiq
	// mcp` from a *graph.Store-backed Store. nil disables generate_flow.
	Flow *flow.Engine

	// Evidence owns the evidence-pack assembler for the
	// `get_evidence_pack` tool. nil disables that tool (the handler
	// returns the legacy "Evidence pack service unavailable. Run
	// 'enrich' first." envelope to match the Java contract).
	Evidence *evidence.Assembler

	// QueryPlanner routes the find_node tool through GRAPH_FIRST /
	// LEXICAL_FIRST / MERGED / DEGRADED. nil falls back to GRAPH_FIRST
	// for every query (legacy behaviour pre-Planner).
	QueryPlanner *iqquery.Planner

	// ArtifactMeta is the most recent provenance snapshot bundled into
	// `get_artifact_metadata` and every evidence pack. nil yields the
	// legacy "Artifact metadata unavailable. Run 'enrich' first."
	// envelope.
	ArtifactMeta *evidence.ArtifactMetadata

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
