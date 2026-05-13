// Consolidated MCP tools — Plan Phase 2.
//
// 34 narrow tools collapsed into 6 mode-driven tools + 1 escape hatch
// (run_cypher, already in tools_graph.go) + 1 review tool (review_changes,
// added in Phase 3). Each consolidated tool takes a `mode` string parameter
// and DELEGATES TO THE EXISTING TOOL HANDLERS — this is a surface change,
// not a query-layer rewrite. The deprecated 34 stay wired for one release
// for back-compat with agents pinned to old names.
//
//   graph_summary        → get_stats, get_detailed_stats, get_capabilities, get_artifact_metadata
//   find_in_graph        → query_nodes, query_edges, search_graph, find_node, find_component_by_file, find_related_endpoints
//   inspect_node         → get_node_neighbors, get_ego_graph, get_evidence_pack, read_file
//   trace_relationships  → find_callers, find_consumers, find_producers, find_dependencies, find_dependents, find_shortest_path
//   analyze_impact       → trace_impact, blast_radius, find_cycles, find_circular_deps, find_dead_code, find_dead_services, find_bottlenecks
//   topology_view        → get_topology, service_detail, service_dependencies, service_dependents, generate_flow
//   run_cypher           → (escape hatch, unchanged)
package mcp

import (
	"context"
	"encoding/json"
	"fmt"
)

// RegisterConsolidated appends the 6 new tools to srv. Wired alongside
// the deprecated 34 by the CLI's mcp boot path.
func RegisterConsolidated(srv *Server, d *Deps) error {
	for _, t := range []Tool{
		toolGraphSummary(d),
		toolFindInGraph(d),
		toolInspectNode(d),
		toolTraceRelationships(d),
		toolAnalyzeImpact(d),
		toolTopologyView(d),
	} {
		if err := srv.Register(t); err != nil {
			return fmt.Errorf("mcp: register consolidated tool %q: %w", t.Name, err)
		}
	}
	return nil
}

// delegate invokes another tool's handler with a synthesized params object.
// Each consolidated tool's mode dispatches through this so the consolidated
// surface stays in lockstep with the deprecated tools — no logic forks.
func delegate(ctx context.Context, t Tool, params map[string]any) (any, error) {
	raw, err := json.Marshal(params)
	if err != nil {
		return NewErrorEnvelope(CodeInternalError, fmt.Errorf("marshal params: %w", err), RequestID(ctx)), nil
	}
	return t.Handler(ctx, raw)
}

func toolGraphSummary(d *Deps) Tool {
	return Tool{
		Name: "graph_summary",
		Description: "Graph overview, statistics, capabilities, and provenance " +
			"in one tool. `mode`: `overview` (totals + breakdowns), " +
			"`categories` (specific category via `category` param), " +
			"`capabilities` (intelligence capability matrix), `provenance` " +
			"(artifact + index timestamps). Default mode: overview.",
		Schema: json.RawMessage(`{"type":"object","properties":{"mode":{"type":"string","enum":["overview","categories","capabilities","provenance"]},"category":{"type":"string"}}}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Mode     string `json:"mode"`
				Category string `json:"category"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.Mode == "" {
				p.Mode = "overview"
			}
			switch p.Mode {
			case "overview":
				return delegate(ctx, toolGetStats(d), nil)
			case "categories":
				return delegate(ctx, toolGetDetailedStats(d), map[string]any{"category": p.Category})
			case "capabilities":
				return delegate(ctx, toolGetCapabilities(d), nil)
			case "provenance":
				return delegate(ctx, toolGetArtifactMetadata(d), nil)
			default:
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("unknown mode %q: expected overview|categories|capabilities|provenance", p.Mode), RequestID(ctx)), nil
			}
		},
	}
}

func toolFindInGraph(d *Deps) Tool {
	return Tool{
		Name: "find_in_graph",
		Description: "Find nodes, edges, or matches. `mode`: " +
			"`nodes` (filter by `kind`), `edges` (filter by `kind`), " +
			"`text` (label search via `query`), `fuzzy` (Planner-routed match via `query`), " +
			"`by_file` (`file_path`), `by_endpoint` (`node_id`).",
		Schema: json.RawMessage(`{"type":"object","properties":{"mode":{"type":"string","enum":["nodes","edges","text","fuzzy","by_file","by_endpoint"]},"kind":{"type":"string"},"query":{"type":"string"},"file_path":{"type":"string"},"node_id":{"type":"string"},"limit":{"type":"integer"}},"required":["mode"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Mode     string `json:"mode"`
				Kind     string `json:"kind"`
				Query    string `json:"query"`
				FilePath string `json:"file_path"`
				NodeID   string `json:"node_id"`
				Limit    int    `json:"limit"`
			}
			if err := json.Unmarshal(raw, &p); err != nil {
				return NewErrorEnvelope(CodeInvalidInput, err, RequestID(ctx)), nil
			}
			switch p.Mode {
			case "nodes":
				return delegate(ctx, toolQueryNodes(d), map[string]any{"kind": p.Kind, "limit": p.Limit})
			case "edges":
				return delegate(ctx, toolQueryEdges(d), map[string]any{"kind": p.Kind, "limit": p.Limit})
			case "text":
				return delegate(ctx, toolSearchGraph(d), map[string]any{"query": p.Query, "limit": p.Limit})
			case "fuzzy":
				return delegate(ctx, toolFindNode(d), map[string]any{"query": p.Query})
			case "by_file":
				return delegate(ctx, toolFindComponentByFile(d), map[string]any{"file_path": p.FilePath})
			case "by_endpoint":
				return delegate(ctx, toolFindRelatedEndpoints(d), map[string]any{"node_id": p.NodeID})
			default:
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("unknown mode %q", p.Mode), RequestID(ctx)), nil
			}
		},
	}
}

func toolInspectNode(d *Deps) Tool {
	return Tool{
		Name: "inspect_node",
		Description: "Inspect a single node. `mode`: " +
			"`neighbors` (1-hop via `node_id` + `direction`), " +
			"`ego` (`center` + `radius`), " +
			"`evidence` (snippets + provenance via `node_id` or `file_path`), " +
			"`source` (file contents via `file_path`).",
		Schema: json.RawMessage(`{"type":"object","properties":{"mode":{"type":"string","enum":["neighbors","ego","evidence","source"]},"node_id":{"type":"string"},"center":{"type":"string"},"radius":{"type":"integer"},"direction":{"type":"string"},"file_path":{"type":"string"}},"required":["mode"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Mode      string `json:"mode"`
				NodeID    string `json:"node_id"`
				Center    string `json:"center"`
				Radius    int    `json:"radius"`
				Direction string `json:"direction"`
				FilePath  string `json:"file_path"`
			}
			if err := json.Unmarshal(raw, &p); err != nil {
				return NewErrorEnvelope(CodeInvalidInput, err, RequestID(ctx)), nil
			}
			switch p.Mode {
			case "neighbors":
				return delegate(ctx, toolGetNodeNeighbors(d), map[string]any{"node_id": p.NodeID, "direction": p.Direction})
			case "ego":
				return delegate(ctx, toolGetEgoGraph(d), map[string]any{"center": p.Center, "radius": p.Radius})
			case "evidence":
				return delegate(ctx, toolGetEvidencePack(d), map[string]any{"node_id": p.NodeID, "file_path": p.FilePath})
			case "source":
				return delegate(ctx, toolReadFile(d), map[string]any{"file_path": p.FilePath})
			default:
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("unknown mode %q", p.Mode), RequestID(ctx)), nil
			}
		},
	}
}

func toolTraceRelationships(d *Deps) Tool {
	return Tool{
		Name: "trace_relationships",
		Description: "Walk relationships from a node. `mode`: " +
			"`callers` | `consumers` | `producers` | `dependencies` | `dependents` " +
			"(all use `node_id`); `shortest_path` (uses `from`+`to`).",
		Schema: json.RawMessage(`{"type":"object","properties":{"mode":{"type":"string","enum":["callers","consumers","producers","dependencies","dependents","shortest_path"]},"node_id":{"type":"string"},"from":{"type":"string"},"to":{"type":"string"}},"required":["mode"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Mode   string `json:"mode"`
				NodeID string `json:"node_id"`
				From   string `json:"from"`
				To     string `json:"to"`
			}
			if err := json.Unmarshal(raw, &p); err != nil {
				return NewErrorEnvelope(CodeInvalidInput, err, RequestID(ctx)), nil
			}
			switch p.Mode {
			case "callers":
				return delegate(ctx, toolFindCallers(d), map[string]any{"node_id": p.NodeID})
			case "consumers":
				return delegate(ctx, toolFindConsumers(d), map[string]any{"node_id": p.NodeID})
			case "producers":
				return delegate(ctx, toolFindProducers(d), map[string]any{"node_id": p.NodeID})
			case "dependencies":
				return delegate(ctx, toolFindDependencies(d), map[string]any{"node_id": p.NodeID})
			case "dependents":
				return delegate(ctx, toolFindDependents(d), map[string]any{"node_id": p.NodeID})
			case "shortest_path":
				return delegate(ctx, toolFindShortestPath(d), map[string]any{"from": p.From, "to": p.To})
			default:
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("unknown mode %q", p.Mode), RequestID(ctx)), nil
			}
		},
	}
}

func toolAnalyzeImpact(d *Deps) Tool {
	return Tool{
		Name: "analyze_impact",
		Description: "Architectural-impact queries. `mode`: " +
			"`blast_radius` (`node_id`+`depth`), `trace` (`node_id`+`depth`), " +
			"`cycles` (`limit`), `circular_deps`, `dead_code` (`kind`+`limit`), " +
			"`dead_services`, `bottlenecks`.",
		Schema: json.RawMessage(`{"type":"object","properties":{"mode":{"type":"string","enum":["blast_radius","trace","cycles","circular_deps","dead_code","dead_services","bottlenecks"]},"node_id":{"type":"string"},"depth":{"type":"integer"},"limit":{"type":"integer"},"kind":{"type":"string"}},"required":["mode"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Mode   string `json:"mode"`
				NodeID string `json:"node_id"`
				Depth  int    `json:"depth"`
				Limit  int    `json:"limit"`
				Kind   string `json:"kind"`
			}
			if err := json.Unmarshal(raw, &p); err != nil {
				return NewErrorEnvelope(CodeInvalidInput, err, RequestID(ctx)), nil
			}
			switch p.Mode {
			case "blast_radius":
				return delegate(ctx, toolBlastRadius(d), map[string]any{"node_id": p.NodeID, "depth": p.Depth})
			case "trace":
				return delegate(ctx, toolTraceImpact(d), map[string]any{"node_id": p.NodeID, "depth": p.Depth})
			case "cycles":
				return delegate(ctx, toolFindCycles(d), map[string]any{"limit": p.Limit})
			case "circular_deps":
				return delegate(ctx, toolFindCircularDeps(d), nil)
			case "dead_code":
				return delegate(ctx, toolFindDeadCode(d), map[string]any{"kind": p.Kind, "limit": p.Limit})
			case "dead_services":
				return delegate(ctx, toolFindDeadServices(d), nil)
			case "bottlenecks":
				return delegate(ctx, toolFindBottlenecks(d), nil)
			default:
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("unknown mode %q", p.Mode), RequestID(ctx)), nil
			}
		},
	}
}

func toolTopologyView(d *Deps) Tool {
	return Tool{
		Name: "topology_view",
		Description: "Service topology + architecture flow diagrams. `mode`: " +
			"`summary`, `service` (`service_name`), `service_deps` (`service_name`), " +
			"`service_dependents` (`service_name`), `flow` (`view`+`format`).",
		Schema: json.RawMessage(`{"type":"object","properties":{"mode":{"type":"string","enum":["summary","service","service_deps","service_dependents","flow"]},"service_name":{"type":"string"},"view":{"type":"string"},"format":{"type":"string","enum":["mermaid","dot","yaml"]}},"required":["mode"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Mode        string `json:"mode"`
				ServiceName string `json:"service_name"`
				View        string `json:"view"`
				Format      string `json:"format"`
			}
			if err := json.Unmarshal(raw, &p); err != nil {
				return NewErrorEnvelope(CodeInvalidInput, err, RequestID(ctx)), nil
			}
			switch p.Mode {
			case "summary":
				return delegate(ctx, toolGetTopology(d), nil)
			case "service":
				return delegate(ctx, toolServiceDetail(d), map[string]any{"service_name": p.ServiceName})
			case "service_deps":
				return delegate(ctx, toolServiceDependencies(d), map[string]any{"service_name": p.ServiceName})
			case "service_dependents":
				return delegate(ctx, toolServiceDependents(d), map[string]any{"service_name": p.ServiceName})
			case "flow":
				return delegate(ctx, toolGenerateFlow(d), map[string]any{"view": p.View, "format": p.Format})
			default:
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("unknown mode %q", p.Mode), RequestID(ctx)), nil
			}
		},
	}
}
