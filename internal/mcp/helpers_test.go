package mcp_test

import (
	"context"
	"encoding/json"
	"testing"
	"time"

	mcpsdk "github.com/modelcontextprotocol/go-sdk/mcp"
	"github.com/randomcodespace/codeiq/internal/mcp"
)

// unmarshalJSON parses a text-content body into a map. Fails the test on
// parse error so individual tool tests stay focused on assertions.
func unmarshalJSON(t *testing.T, body string) map[string]any {
	t.Helper()
	var out map[string]any
	if err := json.Unmarshal([]byte(body), &out); err != nil {
		t.Fatalf("unmarshal: %v\nbody=%s", err, body)
	}
	return out
}

// textContent is an alias for the SDK type so the graph-tool test file
// doesn't need to import mcpsdk directly. The interface is satisfied
// only by *mcpsdk.TextContent — keep the alias pointer-typed.
type textContent = *mcpsdk.TextContent

// connectInMemoryTest is the shared in-memory transport helper used by
// both server_test.go and tools_graph_test.go. The server is started
// on a goroutine; cleanup cancels its context and waits for shutdown.
func connectInMemoryTest(t *testing.T, srv *mcp.Server) (*mcpsdk.ClientSession, func()) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)

	serverT, clientT := mcpsdk.NewInMemoryTransports()
	done := make(chan error, 1)
	go func() { done <- srv.Serve(ctx, serverT) }()

	client := mcpsdk.NewClient(&mcpsdk.Implementation{Name: "test", Version: "0"}, nil)
	sess, err := client.Connect(ctx, clientT, nil)
	if err != nil {
		cancel()
		<-done
		t.Fatalf("client connect: %v", err)
	}
	return sess, func() {
		_ = sess.Close()
		cancel()
		<-done
	}
}

// contextDeadline returns a 5s context for a single CallTool invocation.
func contextDeadline(t *testing.T) (context.Context, context.CancelFunc) {
	t.Helper()
	return context.WithTimeout(context.Background(), 5*time.Second)
}

// sdkCallToolParams builds the SDK params struct for a tool call. args
// may be nil for tools that take no parameters.
func sdkCallToolParams(name string, args map[string]any) *mcpsdk.CallToolParams {
	if args == nil {
		args = map[string]any{}
	}
	return &mcpsdk.CallToolParams{Name: name, Arguments: args}
}
