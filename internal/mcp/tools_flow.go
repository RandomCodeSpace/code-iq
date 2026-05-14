// Tools wiring the architecture-flow MCP tool.
//
// Single tool: generate_flow. Wraps the internal/flow engine; supports
// the five Java views (overview/ci/deploy/runtime/auth) and the four
// renderer formats (json/mermaid/dot/yaml). Mirrors Java McpTools
// .generateFlow but with two extra formats (dot, yaml) that the Java
// renderer also ships now.
package mcp

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/randomcodespace/codeiq/internal/flow"
)

// flowTools returns the slice of flow-facing Tool definitions for d.
// Today there is only one flow tool — leaving the slice plumbing in
// place keeps this file symmetric with tools_graph / tools_topology
// and makes RegisterFlow trivial to extend if drill-down views land.
func flowTools(d *Deps) []Tool {
	return []Tool{toolGenerateFlow(d)}
}

// RegisterFlow appends every flow-facing tool to srv. Symmetric with
// RegisterGraph / RegisterTopology.
func RegisterFlow(srv *Server, d *Deps) error {
	for _, t := range flowTools(d) {
		if err := srv.Register(t); err != nil {
			return fmt.Errorf("mcp: register flow tool %q: %w", t.Name, err)
		}
	}
	return nil
}

// toolGenerateFlow builds the `generate_flow` tool. The view defaults
// to "overview" and the format defaults to "json" — matches the Java
// side defaults. Unknown views surface as INVALID_INPUT (mirrors Java
// IllegalArgumentException → errorEnvelope path) rather than internal
// errors so clients can fix the typo without retrying.
//
// When the engine is not wired (no analysis data) the response mirrors
// the Java contract — a `{ "error": "..." }` envelope rather than a
// generic INTERNAL_ERROR — so existing MCP clients that key off `error`
// keep working unchanged.
func toolGenerateFlow(d *Deps) Tool {
	return Tool{
		Name: "generate_flow",
		Description: "Generate an architecture flow diagram for the " +
			"codebase. Views: overview (full system), ci (build " +
			"pipeline), deploy (deployment topology), runtime (service " +
			"communication), auth (security flow). Output as JSON, " +
			"Mermaid, DOT, or YAML.",
		Schema: json.RawMessage(`{"type":"object","properties":{"view":{"type":"string"},"format":{"type":"string"}}}`),
		Handler: func(ctx context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				View   string `json:"view"`
				Format string `json:"format"`
			}
			_ = json.Unmarshal(raw, &p)
			if p.View == "" {
				p.View = "overview"
			}
			if p.Format == "" {
				p.Format = "json"
			}
			if !flow.IsKnownView(p.View) {
				return NewErrorEnvelope(CodeInvalidInput,
					fmt.Errorf("unknown view %q (valid: overview, ci, deploy, runtime, auth)", p.View),
					RequestID(ctx)), nil
			}
			if d.Flow == nil {
				// Matches Java: "No analysis data available. Run 'codeiq
				// analyze' first." Returned in the `error` legacy field
				// so existing MCP clients keep their existing handling.
				return map[string]string{
					"error": "No analysis data available. Run 'codeiq enrich' first.",
				}, nil
			}
			diag, err := d.Flow.Generate(ctx, flow.View(p.View))
			if err != nil {
				return NewErrorEnvelope(CodeInternalError, err, RequestID(ctx)), nil
			}
			rendered, err := flow.Render(diag, p.Format)
			if err != nil {
				return NewErrorEnvelope(CodeInvalidInput, err, RequestID(ctx)), nil
			}
			// For JSON / YAML we have a text body the client wants raw;
			// for Mermaid / DOT it's already text. Mirror Java's pass-
			// through: return the rendered string directly (the SDK
			// wrapper takes care of converting non-map returns to a
			// text-content body). Wrapping in another JSON envelope
			// would double-encode JSON-shaped output.
			return rendered, nil
		},
	}
}
