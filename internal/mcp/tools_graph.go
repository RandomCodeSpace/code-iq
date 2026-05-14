// Tools wiring the 20 graph-facing MCP tools per spec §8.
//
// Each tool is a `func(ctx, raw json.RawMessage) (any, error)` that
// unmarshals its own typed params, applies the result/depth caps from
// Deps, and delegates to internal/query.Service / Stats / Topology / the
// graph.Store directly. Tools return either a structured payload (which
// the SDK marshals as text content) or an ErrorEnvelope when the input
// is bad. Returning a Go error short-circuits to the SDK's protocol-
// level error envelope — reserve that for genuine internal failures.
package mcp

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/randomcodespace/codeiq/internal/graph"
)

// graphTools returns every graph-tier Tool definition for d — the 18
// narrow tools the consolidated layer delegates to, plus the two
// user-facing tools that survive the consolidation: run_cypher (Cypher
// escape hatch) and read_file (utility).
//
// Production wiring (cli/mcp.go → RegisterGraphUserFacing) registers
// only the 2 user-facing tools; tests that exercise the narrow tool
// implementations directly call RegisterGraph to surface all 20. The
// narrow toolXxx(d) builders are also called from tools_consolidated.go
// for Go-API delegation, independent of MCP registration.
func graphTools(d *Deps) []Tool {
	return []Tool{
		toolGetStats(d),
		toolGetDetailedStats(d),
		toolQueryNodes(d),
		toolQueryEdges(d),
		toolGetNodeNeighbors(d),
		toolGetEgoGraph(d),
		toolFindCycles(d),
		toolFindShortestPath(d),
		toolFindConsumers(d),
		toolFindProducers(d),
		toolFindCallers(d),
		toolFindDependencies(d),
		toolFindDependents(d),
		toolFindDeadCode(d),
		toolFindComponentByFile(d),
		toolTraceImpact(d),
		toolFindRelatedEndpoints(d),
		toolSearchGraph(d),
		toolRunCypher(d),
		toolReadFile(d),
	}
}

// RegisterGraphUserFacing registers only the user-facing graph-tier
// tools (run_cypher + read_file). Used by production cli wiring —
// the 18 narrow tools were dropped from the user MCP surface in
// favor of the 6 consolidated mode-driven tools.
func RegisterGraphUserFacing(srv *Server, d *Deps) error {
	for _, t := range []Tool{toolRunCypher(d), toolReadFile(d)} {
		if err := srv.Register(t); err != nil {
			return fmt.Errorf("mcp: register graph tool %q: %w", t.Name, err)
		}
	}
	return nil
}

// RegisterGraph appends every graph-facing tool to srv. Errors halt the
// loop so a duplicate name surfaces immediately during server boot.
func RegisterGraph(srv *Server, d *Deps) error {
	for _, t := range graphTools(d) {
		if err := srv.Register(t); err != nil {
			return fmt.Errorf("mcp: register graph tool %q: %w", t.Name, err)
		}
	}
	return nil
}

// ---------- tool builders ----------

func toolGetStats(d *Deps) Tool {
	return Tool{
		Name: "get_stats",
		Description: "Get graph overview: total nodes, edges, files, " +
			"languages, and frameworks detected. Use when asked about " +
			"project size, composition, or what was analyzed. Returns JSON " +
			"with counts and breakdowns.",
		Schema: json.RawMessage(`{"type":"object","properties":{}}`),
		Handler: func(ctx context.Context, _ json.RawMessage) (any, error) {
			if d.Stats == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("stats service not wired"), RequestID(ctx)), nil
			}
			return d.Stats.ComputeStats(), nil
		},
	}
}

func toolGetDetailedStats(d *Deps) Tool {
	return Tool{
		Name: "get_detailed_stats",
		Description: "Get categorized statistics: graph metrics, language " +
			"distribution, framework usage, infrastructure, API " +
			"connections, auth patterns, and architecture layers. Use " +
			"for deep project analysis. Filter by category: graph, " +
			"languages, frameworks, infra, connections, auth, " +
			"architecture, or all.",
		Schema: json.RawMessage(`{"type":"object","properties":{"category":{"type":"string","description":"Category filter (default: all)"}}}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Category string `json:"category"`
			}
			_ = json.Unmarshal(raw, &p)
			if d.Stats == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("stats service not wired"), RequestID(ctx)), nil
			}
			cat := p.Category
			if cat == "" || cat == "all" {
				return d.Stats.ComputeStats(), nil
			}
			return d.Stats.ComputeCategory(cat), nil
		},
	}
}

// queryListParams is shared by query_nodes / query_edges. The Java side
// accepts `kind` and `limit`; we match that exactly.
type queryListParams struct {
	Kind  string `json:"kind"`
	Limit int    `json:"limit"`
}

func toolQueryNodes(d *Deps) Tool {
	return Tool{
		Name: "query_nodes",
		Description: "List nodes in the knowledge graph filtered by kind. " +
			"Kinds: endpoint, entity, class, method, guard, service, " +
			"module, topic, queue, config_file, database_connection, " +
			"component, etc. Use when asked 'show me all endpoints' or " +
			"'what entities exist'. Returns paginated node list with " +
			"IDs, labels, and properties.",
		Schema: json.RawMessage(`{"type":"object","properties":{"kind":{"type":"string"},"limit":{"type":"integer"}}}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p queryListParams
			_ = json.Unmarshal(raw, &p)
			if d.Store == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("graph store not wired"), RequestID(ctx)), nil
			}
			if p.Limit == 0 {
				p.Limit = 50
			}
			limit := CapResults(p.Limit, d.MaxResults)
			nodes, err := d.Store.FindByKindPaginated(p.Kind, 0, limit)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return map[string]any{"nodes": nodes, "count": len(nodes), "limit": limit}, nil
		},
	}
}

func toolQueryEdges(d *Deps) Tool {
	return Tool{
		Name: "query_edges",
		Description: "List edges (relationships) in the graph filtered by " +
			"kind. Kinds: calls, imports, depends_on, queries, " +
			"produces, consumes, protects, extends, contains, " +
			"connects_to, etc. Use when asked 'what calls what' or " +
			"'show all dependencies'. Returns paginated edge list.",
		Schema: json.RawMessage(`{"type":"object","properties":{"kind":{"type":"string"},"limit":{"type":"integer"}}}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p queryListParams
			_ = json.Unmarshal(raw, &p)
			if d.Store == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("graph store not wired"), RequestID(ctx)), nil
			}
			if p.Limit == 0 {
				p.Limit = 50
			}
			limit := CapResults(p.Limit, d.MaxResults)
			// Build the rel-table filter — empty kind matches every rel via
			// the anonymous-rel pattern.
			cypher := `MATCH (a:CodeNode)-[r]->(b:CodeNode)
				RETURN a.id AS source, b.id AS target, LABEL(r) AS kind
				ORDER BY source, kind, target LIMIT $lim`
			args := map[string]any{"lim": int64(limit)}
			if p.Kind != "" {
				cypher = `MATCH (a:CodeNode)-[r]->(b:CodeNode) WHERE LABEL(r) = $k
					RETURN a.id AS source, b.id AS target, LABEL(r) AS kind
					ORDER BY source, kind, target LIMIT $lim`
				args["k"] = p.Kind
			}
			rows, err := d.Store.Cypher(cypher, args)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return map[string]any{"edges": rows, "count": len(rows), "limit": limit}, nil
		},
	}
}

func toolGetNodeNeighbors(d *Deps) Tool {
	return Tool{
		Name: "get_node_neighbors",
		Description: "Get all nodes directly connected to a given node, " +
			"with direction control (inbound, outbound, or both). Use " +
			"when asked 'what connects to this service?' or 'what does " +
			"this class depend on?'. Returns neighbor nodes grouped by " +
			"edge kind and direction.",
		Schema: json.RawMessage(`{"type":"object","properties":{"node_id":{"type":"string"},"direction":{"type":"string"}},"required":["node_id"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				NodeID    string `json:"node_id"`
				Direction string `json:"direction"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.NodeID == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("node_id is required"), RequestID(ctx)), nil
			}
			if d.Store == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("graph store not wired"), RequestID(ctx)), nil
			}
			dir := p.Direction
			if dir == "" {
				dir = "both"
			}
			out := map[string]any{"node_id": p.NodeID, "direction": dir}
			if dir == "in" || dir == "both" {
				in, err := d.Store.FindIncomingNeighbors(p.NodeID)
				if err != nil {
					return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
				}
				out["incoming"] = in
			}
			if dir == "out" || dir == "both" {
				outNodes, err := d.Store.FindOutgoingNeighbors(p.NodeID)
				if err != nil {
					return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
				}
				out["outgoing"] = outNodes
			}
			return out, nil
		},
	}
}

func toolGetEgoGraph(d *Deps) Tool {
	return Tool{
		Name: "get_ego_graph",
		Description: "Get the full subgraph within N hops of a center " +
			"node — all reachable nodes and edges. Use for exploring " +
			"the neighborhood of a component, understanding local " +
			"architecture, or visualizing a module's context. Returns " +
			"nodes and edges as a graph structure.",
		Schema: json.RawMessage(`{"type":"object","properties":{"center":{"type":"string"},"radius":{"type":"integer"}},"required":["center"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Center string `json:"center"`
				Radius int    `json:"radius"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.Center == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("center is required"), RequestID(ctx)), nil
			}
			if d.Store == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("graph store not wired"), RequestID(ctx)), nil
			}
			if p.Radius == 0 {
				p.Radius = 2
			}
			depth := CapDepth(p.Radius, d.MaxDepth)
			// Variable-length match centered on Center, walking outbound up to
			// depth. Kuzu's binder is fussy about projecting properties from
			// the endpoint of a variable-length pattern; the supported shape
			// is `properties(nodes(p), 'id')` over the named path. The
			// recursive `[*1..N]` upper bound must be a literal (binder gap);
			// LIMIT goes through parameter binding fine.
			limit := CapResults(0, d.MaxResults)
			cypher := fmt.Sprintf(`
				MATCH p = (c:CodeNode {id: $center})-[*1..%d]-(:CodeNode)
				WITH DISTINCT nodes(p) AS ns
				UNWIND ns AS n
				WITH DISTINCT n
				WHERE n.id <> $center
				RETURN n.id AS id, n.kind AS kind, n.label AS label,
				       n.file_path AS file_path, n.layer AS layer
				ORDER BY n.id LIMIT $lim`, depth)
			rows, err := d.Store.Cypher(cypher, map[string]any{
				"center": p.Center, "lim": int64(limit),
			})
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return map[string]any{
				"center": p.Center,
				"radius": depth,
				"nodes":  rows,
				"count":  len(rows),
			}, nil
		},
	}
}

func toolFindCycles(d *Deps) Tool {
	return Tool{
		Name: "find_cycles",
		Description: "Detect circular dependency cycles in the graph. Use " +
			"when asked about circular dependencies, architecture " +
			"violations, or import loops. Returns list of cycles as " +
			"ordered node ID paths.",
		Schema: json.RawMessage(`{"type":"object","properties":{"limit":{"type":"integer"}}}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Limit int `json:"limit"`
			}
			_ = json.Unmarshal(raw, &p)
			if d.Query == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("query service not wired"), RequestID(ctx)), nil
			}
			if p.Limit <= 0 {
				p.Limit = 100
			}
			limit := CapResults(p.Limit, d.MaxResults)
			cycles, err := d.Query.FindCycles(limit)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return map[string]any{"cycles": cycles, "count": len(cycles)}, nil
		},
	}
}

func toolFindShortestPath(d *Deps) Tool {
	return Tool{
		Name: "find_shortest_path",
		Description: "Find the shortest relationship path between two " +
			"nodes. Use when asked 'how is A connected to B?' or " +
			"'what's the dependency chain from X to Y?'. Returns " +
			"ordered list of nodes along the path.",
		Schema: json.RawMessage(`{"type":"object","properties":{"source":{"type":"string"},"target":{"type":"string"}},"required":["source","target"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Source string `json:"source"`
				Target string `json:"target"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.Source == "" || p.Target == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("source and target are required"), RequestID(ctx)), nil
			}
			if d.Query == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("query service not wired"), RequestID(ctx)), nil
			}
			path, err := d.Query.FindShortestPath(p.Source, p.Target)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			if len(path) == 0 {
				return map[string]any{"error": fmt.Sprintf("No path found between %s and %s", p.Source, p.Target)}, nil
			}
			return map[string]any{"path": path, "length": len(path) - 1}, nil
		},
	}
}

// consumerLikeTool builds a Tool that takes a `target_id` and runs `fn`
// against it. Five tools (consumers/producers/callers/dependencies/
// dependents) share this exact shape — the only difference is the
// service method invoked.
func consumerLikeTool(name, description string, fn func(id string) (any, error)) Tool {
	return Tool{
		Name:        name,
		Description: description,
		Schema:      json.RawMessage(`{"type":"object","properties":{"target_id":{"type":"string"}},"required":["target_id"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				TargetID string `json:"target_id"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.TargetID == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("target_id is required"), RequestID(ctx)), nil
			}
			out, err := fn(p.TargetID)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return out, nil
		},
	}
}

func toolFindConsumers(d *Deps) Tool {
	return consumerLikeTool("find_consumers",
		"Find all services, handlers, or functions that consume/listen "+
			"from a given topic, queue, or event source. Use when asked "+
			"'what reads from this topic?' or 'who listens to this event?'.",
		func(id string) (any, error) {
			if d.Query == nil {
				return nil, fmt.Errorf("query service not wired")
			}
			nodes, err := d.Query.FindConsumers(id)
			if err != nil {
				return nil, err
			}
			return map[string]any{"consumers": nodes, "count": len(nodes)}, nil
		})
}

func toolFindProducers(d *Deps) Tool {
	return consumerLikeTool("find_producers",
		"Find all services or functions that produce/publish to a given "+
			"topic, queue, or event target. Use when asked 'what writes "+
			"to this topic?' or 'who publishes to this queue?'.",
		func(id string) (any, error) {
			if d.Query == nil {
				return nil, fmt.Errorf("query service not wired")
			}
			nodes, err := d.Query.FindProducers(id)
			if err != nil {
				return nil, err
			}
			return map[string]any{"producers": nodes, "count": len(nodes)}, nil
		})
}

func toolFindCallers(d *Deps) Tool {
	return consumerLikeTool("find_callers",
		"Find all methods or services that call a given target function, "+
			"method, or service. Use when asked 'who calls this method?' "+
			"or 'what invokes this service?'.",
		func(id string) (any, error) {
			if d.Query == nil {
				return nil, fmt.Errorf("query service not wired")
			}
			nodes, err := d.Query.FindCallers(id)
			if err != nil {
				return nil, err
			}
			return map[string]any{"callers": nodes, "count": len(nodes)}, nil
		})
}

func toolFindDependencies(d *Deps) Tool {
	return consumerLikeTool("find_dependencies",
		"Find all modules, services, or packages that a given module "+
			"depends on (outbound dependencies). Use when asked 'what "+
			"does this service depend on?'.",
		func(id string) (any, error) {
			if d.Query == nil {
				return nil, fmt.Errorf("query service not wired")
			}
			nodes, err := d.Query.FindDependencies(id)
			if err != nil {
				return nil, err
			}
			return map[string]any{"dependencies": nodes, "count": len(nodes)}, nil
		})
}

func toolFindDependents(d *Deps) Tool {
	return consumerLikeTool("find_dependents",
		"Find all modules or services that depend on a given module "+
			"(inbound — who uses it). Use when asked 'what breaks if I "+
			"change this?' or 'who depends on this library?'.",
		func(id string) (any, error) {
			if d.Query == nil {
				return nil, fmt.Errorf("query service not wired")
			}
			nodes, err := d.Query.FindDependents(id)
			if err != nil {
				return nil, err
			}
			return map[string]any{"dependents": nodes, "count": len(nodes)}, nil
		})
}

func toolFindDeadCode(d *Deps) Tool {
	return Tool{
		Name: "find_dead_code",
		Description: "Find potentially unreachable code: classes, methods, " +
			"or interfaces with no incoming calls, imports, or " +
			"references. Use when asked about unused code, cleanup " +
			"candidates, or dead code analysis. Filter by kind (class, " +
			"method, interface). Returns nodes that appear isolated.",
		Schema: json.RawMessage(`{"type":"object","properties":{"kind":{"type":"string"},"limit":{"type":"integer"}}}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Kind  string `json:"kind"`
				Limit int    `json:"limit"`
			}
			_ = json.Unmarshal(raw, &p)
			if d.Query == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("query service not wired"), RequestID(ctx)), nil
			}
			if p.Limit <= 0 {
				p.Limit = 100
			}
			limit := CapResults(p.Limit, d.MaxResults)
			var kinds []string
			if p.Kind != "" {
				kinds = []string{p.Kind}
			}
			dead, err := d.Query.FindDeadCode(kinds, limit)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return map[string]any{"dead_code": dead, "count": len(dead)}, nil
		},
	}
}

func toolFindComponentByFile(d *Deps) Tool {
	return Tool{
		Name: "find_component_by_file",
		Description: "Given a source file path, find which module/service " +
			"it belongs to, its architecture layer (frontend/backend/" +
			"infra), and all nodes defined in that file. Use when asked " +
			"'what component is this file part of?' or for file-level " +
			"triage.",
		Schema: json.RawMessage(`{"type":"object","properties":{"file_path":{"type":"string"}},"required":["file_path"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				FilePath string `json:"file_path"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.FilePath == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("file_path is required"), RequestID(ctx)), nil
			}
			if d.Store == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("graph store not wired"), RequestID(ctx)), nil
			}
			// Walk nodes whose file_path matches. Kuzu 0.7 doesn't handle the
			// OPTIONAL MATCH variant cleanly here (binder doesn't re-scope
			// `n` after the OPTIONAL), so split into two queries: first
			// fetch the nodes, then look up service containment per node.
			rows, err := d.Store.Cypher(`
				MATCH (n:CodeNode) WHERE n.file_path = $f
				RETURN n.id AS id, n.kind AS kind, n.label AS label, n.layer AS layer
				ORDER BY n.id`, map[string]any{"f": p.FilePath})
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			// Second pass: annotate each node with its parent service name
			// (if any) via a direct CONTAINS lookup. Keeps the result shape
			// uniform with the Java side without requiring OPTIONAL MATCH.
			for _, r := range rows {
				id, ok := r["id"].(string)
				if !ok {
					continue
				}
				svc, err := d.Store.Cypher(`
					MATCH (s:CodeNode)-[:CONTAINS]->(n:CodeNode {id: $id})
					WHERE s.kind = 'service'
					RETURN s.label AS service LIMIT 1`, map[string]any{"id": id})
				if err == nil && len(svc) > 0 {
					r["service"] = svc[0]["service"]
				}
			}
			return map[string]any{"file_path": p.FilePath, "nodes": rows, "count": len(rows)}, nil
		},
	}
}

func toolTraceImpact(d *Deps) Tool {
	return Tool{
		Name: "trace_impact",
		Description: "Trace the downstream blast radius of a node — " +
			"everything that depends on it, transitively up to N hops. " +
			"Use when asked 'what breaks if I change this?' or 'what's " +
			"the impact of modifying this service?'. Returns affected " +
			"nodes grouped by depth.",
		Schema: json.RawMessage(`{"type":"object","properties":{"node_id":{"type":"string"},"depth":{"type":"integer"}},"required":["node_id"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				NodeID string `json:"node_id"`
				Depth  int    `json:"depth"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.NodeID == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("node_id is required"), RequestID(ctx)), nil
			}
			if d.Topology == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("topology service not wired"), RequestID(ctx)), nil
			}
			if p.Depth == 0 {
				p.Depth = 3
			}
			depth := CapDepth(p.Depth, d.MaxDepth)
			out, err := d.Topology.BlastRadius(p.NodeID, depth)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return out, nil
		},
	}
}

func toolFindRelatedEndpoints(d *Deps) Tool {
	return Tool{
		Name: "find_related_endpoints",
		Description: "Given a file, class, or entity name, find all REST/" +
			"gRPC/GraphQL endpoints that interact with it. Use when " +
			"asked 'which APIs use this entity?' or 'what endpoints " +
			"touch the User table?'. Returns endpoint nodes with HTTP " +
			"methods and paths.",
		Schema: json.RawMessage(`{"type":"object","properties":{"identifier":{"type":"string"}},"required":["identifier"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Identifier string `json:"identifier"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.Identifier == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("identifier is required"), RequestID(ctx)), nil
			}
			if d.Store == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("graph store not wired"), RequestID(ctx)), nil
			}
			limit := CapResults(50, d.MaxResults)
			// Endpoints that share a service container with the identifier
			// (file path / class / fqn) — the simplest semantic match that
			// works across languages.
			cypher := `
				MATCH (target:CodeNode)
				WHERE target.file_path = $i OR target.label = $i OR target.id = $i OR target.fqn = $i
				MATCH (target)<-[:CONTAINS]-(svc:CodeNode {kind: 'service'})-[:CONTAINS]->(ep:CodeNode)
				WHERE ep.kind = 'endpoint' OR ep.kind = 'websocket_endpoint'
				RETURN DISTINCT ep.id AS id, ep.kind AS kind, ep.label AS label,
				                ep.file_path AS file_path, ep.layer AS layer,
				                svc.label AS service
				ORDER BY ep.id LIMIT $lim`
			rows, err := d.Store.Cypher(cypher, map[string]any{"i": p.Identifier, "lim": int64(limit)})
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return map[string]any{"identifier": p.Identifier, "endpoints": rows, "count": len(rows)}, nil
		},
	}
}

func toolSearchGraph(d *Deps) Tool {
	return Tool{
		Name: "search_graph",
		Description: "Full-text search across all node labels, IDs, file " +
			"paths, and properties. Use as the starting point when the " +
			"user mentions a name but you don't have the exact node ID. " +
			"Returns matching nodes ranked by relevance.",
		Schema: json.RawMessage(`{"type":"object","properties":{"query":{"type":"string"},"limit":{"type":"integer"}},"required":["query"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Query string `json:"query"`
				Limit int    `json:"limit"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.Query == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("query is required"), RequestID(ctx)), nil
			}
			if d.Store == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("graph store not wired"), RequestID(ctx)), nil
			}
			if p.Limit == 0 {
				p.Limit = 20
			}
			limit := CapResults(p.Limit, d.MaxResults)
			nodes, err := d.Store.SearchByLabel(p.Query, limit)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return map[string]any{"query": p.Query, "results": nodes, "count": len(nodes)}, nil
		},
	}
}

func toolRunCypher(d *Deps) Tool {
	return Tool{
		Name: "run_cypher",
		Description: "Execute a custom read-only Cypher query directly " +
			"against the Kuzu graph. CALL db.* / show_* / table_* " +
			"read-only procedures are allowed. Mutation queries " +
			"(CREATE, DELETE, SET, MERGE, etc.) are blocked.",
		Schema: json.RawMessage(`{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Query string `json:"query"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.Query == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("query is required"), RequestID(ctx)), nil
			}
			if d.Store == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("graph store not wired"), RequestID(ctx)), nil
			}
			// Belt-and-braces: gate before hitting the store. The store's
			// own gate would also catch this in read-only mode, but
			// returning a structured response (rather than a Go error)
			// keeps the wire shape consistent with the Java side.
			if kw := graph.MutationKeyword(p.Query); kw != "" {
				return map[string]string{
					"error": "Read-only queries only. Mutation keyword found: " + kw,
				}, nil
			}
			maxRows := d.MaxResults
			if maxRows <= 0 {
				maxRows = DefaultMaxResults
			}
			rows, truncated, err := d.Store.CypherRows(p.Query, nil, maxRows)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			out := map[string]any{"rows": rows, "count": len(rows)}
			if truncated {
				out["truncated"] = true
				out["max_results"] = maxRows
			}
			return out, nil
		},
	}
}

func toolReadFile(d *Deps) Tool {
	return Tool{
		Name: "read_file",
		Description: "Read source file content from the analyzed codebase. " +
			"Supports full file or line range. Use when you need to " +
			"show actual code to the user, verify a detection result, " +
			"or provide code context. Returns raw file content as text.",
		Schema: json.RawMessage(`{"type":"object","properties":{"file_path":{"type":"string"},"start_line":{"type":"integer"},"end_line":{"type":"integer"}},"required":["file_path"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				FilePath  string `json:"file_path"`
				StartLine int    `json:"start_line"`
				EndLine   int    `json:"end_line"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.FilePath == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("file_path is required"), RequestID(ctx)), nil
			}
			if d.RootPath == "" {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("root path not configured — read_file disabled"), RequestID(ctx)), nil
			}
			resp, err := ReadRepoFile(ReadFileRequest{
				Root:      d.RootPath,
				Path:      p.FilePath,
				StartLine: p.StartLine,
				EndLine:   p.EndLine,
				MaxBytes:  2 * 1024 * 1024,
			})
			if err != nil {
				return NewErrorEnvelope(CodeFileReadFailed, err, RequestID(ctx)), nil
			}
			return resp, nil
		},
	}
}


