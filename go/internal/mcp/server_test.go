package mcp_test

import (
	"context"
	"encoding/json"
	"strings"
	"testing"
	"time"

	mcpsdk "github.com/modelcontextprotocol/go-sdk/mcp"
	"github.com/randomcodespace/codeiq/go/internal/mcp"
)

// connectInMemory wires a client + server pair through the SDK's
// in-memory transports. Returns the client session and a cancel func
// the test should defer. The server is started on a goroutine; cancel
// shuts both sides down.
func connectInMemory(t *testing.T, srv *mcp.Server) (*mcpsdk.ClientSession, func()) {
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

func TestNewServerRequiresName(t *testing.T) {
	if _, err := mcp.NewServer(mcp.ServerOptions{}); err == nil {
		t.Fatalf("expected error when Name is empty")
	}
	if _, err := mcp.NewServer(mcp.ServerOptions{Name: "x"}); err != nil {
		t.Fatalf("NewServer with Name failed: %v", err)
	}
}

func TestServerInitializeHandshake(t *testing.T) {
	srv, err := mcp.NewServer(mcp.ServerOptions{Name: "codeiq-test", Version: "0.0.0-test"})
	if err != nil {
		t.Fatalf("NewServer: %v", err)
	}
	sess, cleanup := connectInMemory(t, srv)
	defer cleanup()

	got := sess.InitializeResult()
	if got == nil {
		t.Fatalf("InitializeResult is nil — handshake did not complete")
	}
	if got.ServerInfo == nil {
		t.Fatalf("ServerInfo is nil in initialize result")
	}
	if got.ServerInfo.Name != "codeiq-test" {
		t.Fatalf("ServerInfo.Name = %q, want %q", got.ServerInfo.Name, "codeiq-test")
	}
	if got.ServerInfo.Version != "0.0.0-test" {
		t.Fatalf("ServerInfo.Version = %q, want %q", got.ServerInfo.Version, "0.0.0-test")
	}
}

func TestServerListsRegisteredTools(t *testing.T) {
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "codeiq-test", Version: "0"})
	if err := srv.Register(mcp.Tool{
		Name:        "ping",
		Description: "Replies with pong.",
		Schema:      json.RawMessage(`{"type":"object","properties":{}}`),
		Handler: func(_ context.Context, _ json.RawMessage) (any, error) {
			return map[string]string{"reply": "pong"}, nil
		},
	}); err != nil {
		t.Fatalf("Register: %v", err)
	}
	sess, cleanup := connectInMemory(t, srv)
	defer cleanup()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	list, err := sess.ListTools(ctx, nil)
	if err != nil {
		t.Fatalf("ListTools: %v", err)
	}
	if len(list.Tools) != 1 || list.Tools[0].Name != "ping" {
		names := make([]string, 0, len(list.Tools))
		for _, tl := range list.Tools {
			names = append(names, tl.Name)
		}
		t.Fatalf("ListTools returned %v, want [ping]", names)
	}
}

func TestServerCallsRegisteredTool(t *testing.T) {
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "codeiq-test", Version: "0"})
	_ = srv.Register(mcp.Tool{
		Name:        "echo",
		Description: "Echoes its input back as a JSON object.",
		Schema:      json.RawMessage(`{"type":"object","properties":{"msg":{"type":"string"}}}`),
		Handler: func(_ context.Context, raw json.RawMessage) (any, error) {
			var p struct {
				Msg string `json:"msg"`
			}
			_ = json.Unmarshal(raw, &p)
			return map[string]string{"echoed": p.Msg}, nil
		},
	})
	sess, cleanup := connectInMemory(t, srv)
	defer cleanup()

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	res, err := sess.CallTool(ctx, &mcpsdk.CallToolParams{
		Name:      "echo",
		Arguments: map[string]any{"msg": "hello"},
	})
	if err != nil {
		t.Fatalf("CallTool: %v", err)
	}
	if len(res.Content) == 0 {
		t.Fatalf("empty content")
	}
	tc, ok := res.Content[0].(*mcpsdk.TextContent)
	if !ok {
		t.Fatalf("content type = %T, want *TextContent", res.Content[0])
	}
	if !strings.Contains(tc.Text, `"echoed":"hello"`) {
		t.Fatalf("text = %q, want echoed:hello substring", tc.Text)
	}
}

func TestRegistryRejectsDuplicateAndEmpty(t *testing.T) {
	srv, _ := mcp.NewServer(mcp.ServerOptions{Name: "x", Version: "0"})
	tool := mcp.Tool{
		Name:        "a",
		Description: "d",
		Schema:      json.RawMessage(`{"type":"object"}`),
		Handler: func(_ context.Context, _ json.RawMessage) (any, error) {
			return nil, nil
		},
	}
	if err := srv.Register(tool); err != nil {
		t.Fatalf("first Register: %v", err)
	}
	if err := srv.Register(tool); err == nil {
		t.Fatalf("expected duplicate error")
	}
	if err := srv.Register(mcp.Tool{}); err == nil {
		t.Fatalf("expected empty-name error")
	}
	if err := srv.Register(mcp.Tool{Name: "z"}); err == nil {
		t.Fatalf("expected nil-handler error")
	}
}
