// Tools wiring the 9 topology-facing MCP tools per spec §8.
//
// Each tool delegates to internal/query.Topology — the Java side's
// getCachedData() 60s heap snapshot is NOT replicated; per spec §8 and
// the documented gotcha each topology operation runs targeted Cypher
// against the structural CONTAINS edges so peak memory stays flat
// regardless of graph size.
package mcp

import (
	"context"
	"encoding/json"
	"fmt"
)

// topologyTools returns the slice of topology-facing Tool definitions
// for d. Each tool is fully self-contained — no shared mutable state.
// The returned slice is registered in order by RegisterTopology.
func topologyTools(d *Deps) []Tool {
	return []Tool{
		toolGetTopology(d),
		toolServiceDetail(d),
		toolServiceDependencies(d),
		toolServiceDependents(d),
		toolBlastRadius(d),
		toolFindPath(d),
		toolFindBottlenecks(d),
		toolFindCircularDeps(d),
		toolFindDeadServices(d),
	}
}

// RegisterTopology appends every topology-facing tool to srv. Errors
// halt the loop so a duplicate name surfaces immediately at server
// boot. Symmetric with RegisterGraph; designed to be invoked once at
// startup.
func RegisterTopology(srv *Server, d *Deps) error {
	for _, t := range topologyTools(d) {
		if err := srv.Register(t); err != nil {
			return fmt.Errorf("mcp: register topology tool %q: %w", t.Name, err)
		}
	}
	return nil
}

// ---------- tool builders ----------

func toolGetTopology(d *Deps) Tool {
	return Tool{
		Name: "get_topology",
		Description: "Get the service topology map: all services, " +
			"infrastructure nodes (databases, message queues, caches), " +
			"and runtime connections between them. Use when asked about " +
			"service architecture, system overview, or 'how do services " +
			"communicate?'. Returns services with connection counts and " +
			"infrastructure details.",
		Schema: json.RawMessage(`{"type":"object","properties":{}}`),
		Handler: func(ctx context.Context, _ json.RawMessage) (any, error) {
			if d.Topology == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("topology service not wired"), RequestID(ctx)), nil
			}
			out, err := d.Topology.GetTopology()
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return out, nil
		},
	}
}

func toolServiceDetail(d *Deps) Tool {
	return Tool{
		Name: "service_detail",
		Description: "Get comprehensive details about a specific service: " +
			"its endpoints, entities, dependencies, dependents, guards, " +
			"infrastructure connections, and node counts by kind. Use " +
			"when asked 'tell me about the order-service' or for deep-" +
			"diving into one service.",
		Schema: json.RawMessage(`{"type":"object","properties":{"service_name":{"type":"string"}},"required":["service_name"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				ServiceName string `json:"service_name"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.ServiceName == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("service_name is required"), RequestID(ctx)), nil
			}
			if d.Topology == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("topology service not wired"), RequestID(ctx)), nil
			}
			out, err := d.Topology.ServiceDetail(p.ServiceName)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return out, nil
		},
	}
}

func toolServiceDependencies(d *Deps) Tool {
	return Tool{
		Name: "service_dependencies",
		Description: "List everything a service depends on: databases it " +
			"queries, queues it produces to, other services it calls, " +
			"caches it uses. Use when asked 'what does this service " +
			"need to run?' or 'what are its downstream dependencies?'.",
		Schema: json.RawMessage(`{"type":"object","properties":{"service_name":{"type":"string"}},"required":["service_name"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				ServiceName string `json:"service_name"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.ServiceName == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("service_name is required"), RequestID(ctx)), nil
			}
			if d.Topology == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("topology service not wired"), RequestID(ctx)), nil
			}
			out, err := d.Topology.ServiceDependencies(p.ServiceName)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return out, nil
		},
	}
}

func toolServiceDependents(d *Deps) Tool {
	return Tool{
		Name: "service_dependents",
		Description: "List all services and components that depend on " +
			"this service — its upstream consumers. Use when asked " +
			"'who calls this service?' or 'what breaks if this service " +
			"goes down?'.",
		Schema: json.RawMessage(`{"type":"object","properties":{"service_name":{"type":"string"}},"required":["service_name"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				ServiceName string `json:"service_name"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.ServiceName == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("service_name is required"), RequestID(ctx)), nil
			}
			if d.Topology == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("topology service not wired"), RequestID(ctx)), nil
			}
			out, err := d.Topology.ServiceDependents(p.ServiceName)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return out, nil
		},
	}
}

func toolBlastRadius(d *Deps) Tool {
	return Tool{
		Name: "blast_radius",
		Description: "Analyze the blast radius of a node: all nodes " +
			"affected if it changes, grouped by hop distance. Use for " +
			"change impact analysis, incident triage, or understanding " +
			"coupling. Returns affected nodes with paths showing how " +
			"they're connected.",
		Schema: json.RawMessage(`{"type":"object","properties":{"node_id":{"type":"string"}},"required":["node_id"]}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				NodeID string `json:"node_id"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.NodeID == "" {
				return NewErrorEnvelope(CodeInvalidInput, fmt.Errorf("node_id is required"), RequestID(ctx)), nil
			}
			if d.Topology == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("topology service not wired"), RequestID(ctx)), nil
			}
			depth := CapDepth(0, d.MaxDepth)
			out, err := d.Topology.BlastRadius(p.NodeID, depth)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return out, nil
		},
	}
}

func toolFindPath(d *Deps) Tool {
	return Tool{
		Name: "find_path",
		Description: "Find the connection path between two services in " +
			"the topology. Use when asked 'how does service A talk to " +
			"service B?' or 'what's the chain between frontend and " +
			"database?'. Returns the ordered path of services and " +
			"connections.",
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
			if d.Topology == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("topology service not wired"), RequestID(ctx)), nil
			}
			hops, err := d.Topology.FindPath(p.Source, p.Target)
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			if hops == nil {
				return map[string]any{
					"source": p.Source,
					"target": p.Target,
					"error":  fmt.Sprintf("No path found between %s and %s", p.Source, p.Target),
				}, nil
			}
			return map[string]any{
				"source": p.Source,
				"target": p.Target,
				"path":   hops,
				"length": len(hops),
			}, nil
		},
	}
}

func toolFindBottlenecks(d *Deps) Tool {
	return Tool{
		Name: "find_bottlenecks",
		Description: "Identify bottleneck services with the most inbound " +
			"and outbound connections — high-traffic hubs that are " +
			"potential single points of failure. Use when asked about " +
			"architecture risks, scaling concerns, or 'which services " +
			"are most critical?'.",
		Schema: json.RawMessage(`{"type":"object","properties":{}}`),
		Handler: func(ctx context.Context, _ json.RawMessage) (any, error) {
			if d.Topology == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("topology service not wired"), RequestID(ctx)), nil
			}
			rows, err := d.Topology.FindBottlenecks()
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return map[string]any{
				"bottlenecks": rows,
				"count":       len(rows),
			}, nil
		},
	}
}

func toolFindCircularDeps(d *Deps) Tool {
	return Tool{
		Name: "find_circular_deps",
		Description: "Detect circular dependencies between services " +
			"(A->B->C->A). Use when asked about architecture health, " +
			"deployment order issues, or 'are there any circular " +
			"service dependencies?'. Returns cycles as ordered service " +
			"name lists.",
		Schema: json.RawMessage(`{"type":"object","properties":{}}`),
		Handler: func(ctx context.Context, _ json.RawMessage) (any, error) {
			if d.Topology == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("topology service not wired"), RequestID(ctx)), nil
			}
			cycles, err := d.Topology.FindCircular()
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return map[string]any{
				"cycles": cycles,
				"count":  len(cycles),
			}, nil
		},
	}
}

func toolFindDeadServices(d *Deps) Tool {
	return Tool{
		Name: "find_dead_services",
		Description: "Find services with zero incoming connections — " +
			"potentially unused or orphaned services. Use when asked " +
			"about cleanup opportunities or 'are there any services " +
			"nothing calls?'. Returns isolated service nodes.",
		Schema: json.RawMessage(`{"type":"object","properties":{}}`),
		Handler: func(ctx context.Context, _ json.RawMessage) (any, error) {
			if d.Topology == nil {
				return NewErrorEnvelope(CodeInternalError, fmt.Errorf("topology service not wired"), RequestID(ctx)), nil
			}
			rows, err := d.Topology.FindDeadServices()
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			return map[string]any{
				"dead_services": rows,
				"count":         len(rows),
			}, nil
		},
	}
}
