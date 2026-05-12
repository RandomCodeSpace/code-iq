// Package mcp implements the codeiq stdio MCP server.
//
// The server is created once per process by `codeiq mcp`, opens Kuzu in
// read-only mode, registers all 34 tools, and runs the JSON-RPC protocol
// loop via the official Anthropic Go SDK
// (github.com/modelcontextprotocol/go-sdk). Stdin is the JSON-RPC frame
// reader, stdout the writer, and stderr the log channel. There is no
// HTTP transport at this layer — codeiq's HTTP surface lives in
// `internal/api` and is independent.
//
// SDK pin: github.com/modelcontextprotocol/go-sdk v1.6.0 (latest stable
// at phase 3 start). The plan was drafted against v0.4.0 — the public
// API moved between those versions:
//
//   - Plan was written against an older SDK shape that exposed
//     `mcpsdk.NewStdioTransport(in, out)`. In v1.x the stdio transport
//     is a zero-value `&mcpsdk.StdioTransport{}` hard-bound to os.Stdin
//     and os.Stdout — there is no way to inject pipes. Tests use
//     `mcpsdk.NewInMemoryTransports()` and call `Server.Run(ctx, transport)`
//     for both sides; the CLI passes `&mcpsdk.StdioTransport{}`.
//   - Plan referenced `mcpsdk.ServerTool` (a {Tool, Handler} pair). v1.x
//     replaces this with `Server.AddTool(t *Tool, h ToolHandler)` where
//     ToolHandler is `func(ctx, *CallToolRequest) (*CallToolResult, error)`.
//     The request's `Params.Arguments` is `json.RawMessage` per
//     `CallToolParamsRaw`.
//
// Our wrapper hides those differences: callers register `mcp.Tool`
// (name + description + JSON schema + handler) via `Server.Register`, and
// `Serve(ctx, transport)` delegates to the SDK's `Server.Run`.
package mcp

import (
	"context"
	"encoding/json"
	"fmt"

	mcpsdk "github.com/modelcontextprotocol/go-sdk/mcp"
)

// Handler runs a single tool invocation. params is the raw JSON object
// sent by the client; the handler unmarshals into its own typed struct.
// The returned value is JSON-marshaled and wrapped as a text-content
// CallToolResult. Returning an error short-circuits to the SDK error
// envelope — most tools should instead return an `ErrorEnvelope` value
// and a nil error so the result reaches the client as structured JSON.
type Handler func(ctx context.Context, params json.RawMessage) (any, error)

// Tool is a single MCP tool: name, description, JSON-Schema for params,
// and a handler. Schemas are hand-written as `json.RawMessage` to mirror
// the Java side's `@McpToolParam` descriptions verbatim — the v0.8.0 SDK
// accepts any value that JSON-marshals to a valid schema in `InputSchema`.
type Tool struct {
	Name        string
	Description string
	Schema      json.RawMessage
	Handler     Handler
}

// asSDKTool converts the wrapper Tool into the SDK's (*Tool, ToolHandler)
// pair. The returned handler unmarshals `req.Params.Arguments` (already a
// json.RawMessage on the v0.8.0 server side) and wraps the handler's
// return value as text content. If `t.Schema` is nil, an empty
// `{"type":"object"}` is substituted because `Server.AddTool` panics when
// InputSchema is missing.
func (t Tool) asSDKTool() (*mcpsdk.Tool, mcpsdk.ToolHandler) {
	schema := t.Schema
	if len(schema) == 0 {
		schema = json.RawMessage(`{"type":"object","properties":{}}`)
	}
	sdkTool := &mcpsdk.Tool{
		Name:        t.Name,
		Description: t.Description,
		InputSchema: schema,
	}
	handler := func(ctx context.Context, req *mcpsdk.CallToolRequest) (*mcpsdk.CallToolResult, error) {
		var raw json.RawMessage
		if req != nil && req.Params != nil {
			raw = req.Params.Arguments
		}
		if len(raw) == 0 {
			raw = json.RawMessage(`{}`)
		}
		out, err := t.Handler(ctx, raw)
		if err != nil {
			return nil, err
		}
		body, err := json.Marshal(out)
		if err != nil {
			return nil, fmt.Errorf("mcp: marshal tool result for %q: %w", t.Name, err)
		}
		return &mcpsdk.CallToolResult{
			Content: []mcpsdk.Content{&mcpsdk.TextContent{Text: string(body)}},
		}, nil
	}
	return sdkTool, handler
}
