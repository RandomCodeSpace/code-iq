//go:build integration

// End-to-end MCP integration test. Spawns the real `codeiq mcp` binary,
// exchanges JSON-RPC frames over its stdin / stdout, and asserts the
// initialize handshake completes and tools/list returns the 10
// user-facing tools (2 graph + 1 flow + 6 consolidated + 1 review).
//
// Build tag `integration` keeps this out of the default `go test ./...`
// loop because it does a full `go build` first and stands up a fresh
// Kuzu store on disk. Run explicitly via:
//
//	CGO_ENABLED=1 go test -tags integration ./internal/mcp/...
//
// The test fails fast on any IO or JSON parse error — there is no
// retry loop. The integration surface should be deterministic; flakes
// here are bugs.
package mcp_test

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/randomcodespace/codeiq/internal/graph"
)

// buildCodeiq compiles `cmd/codeiq` into a tmp binary so the test runs
// against an actual on-disk artifact rather than the test's own
// process. Returns the binary path.
func buildCodeiq(t *testing.T) string {
	t.Helper()
	bin := filepath.Join(t.TempDir(), "codeiq")
	// `go build` resolves relative to the working dir; we build from
	// the repo root so `./cmd/codeiq` is correct.
	cwd, err := os.Getwd()
	if err != nil {
		t.Fatalf("getwd: %v", err)
	}
	// `go test` cwd is the package dir (internal/mcp); back up two
	// levels to the `go/` module root.
	moduleRoot := filepath.Join(cwd, "..", "..")
	cmd := exec.Command("go", "build", "-o", bin, "./cmd/codeiq")
	cmd.Dir = moduleRoot
	cmd.Env = append(os.Environ(), "CGO_ENABLED=1")
	out, err := cmd.CombinedOutput()
	if err != nil {
		t.Fatalf("go build: %v\n%s", err, out)
	}
	return bin
}

// seedEmptyGraph stands up a fresh Kuzu store with schema applied so
// `codeiq mcp` has something to open. Returns the graph dir path.
func seedEmptyGraph(t *testing.T) string {
	t.Helper()
	dir := filepath.Join(t.TempDir(), "graph.kuzu")
	s, err := graph.Open(dir)
	if err != nil {
		t.Fatalf("graph.Open: %v", err)
	}
	if err := s.ApplySchema(); err != nil {
		t.Fatalf("ApplySchema: %v", err)
	}
	if err := s.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	return dir
}

// mcpClient wraps the spawned binary's stdin/stdout into a newline-
// delimited JSON-RPC peer. Caller drives the conversation explicitly —
// no implicit framing.
type mcpClient struct {
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	stdout *bufio.Reader
	stderr *bufio.Reader
}

// startCodeiqMCP spawns `<bin> mcp --graph-dir <dir>` with a fresh stdio
// peer ready to use. Returns the client + a teardown closure that signals
// the process and reaps stderr.
func startCodeiqMCP(t *testing.T, bin, graphDir, root string) (*mcpClient, func()) {
	t.Helper()
	cmd := exec.Command(bin, "mcp", "--graph-dir", graphDir, root)

	stdinPipe, err := cmd.StdinPipe()
	if err != nil {
		t.Fatalf("StdinPipe: %v", err)
	}
	stdoutPipe, err := cmd.StdoutPipe()
	if err != nil {
		t.Fatalf("StdoutPipe: %v", err)
	}
	stderrPipe, err := cmd.StderrPipe()
	if err != nil {
		t.Fatalf("StderrPipe: %v", err)
	}
	if err := cmd.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	client := &mcpClient{
		cmd:    cmd,
		stdin:  stdinPipe,
		stdout: bufio.NewReader(stdoutPipe),
		stderr: bufio.NewReader(stderrPipe),
	}
	cleanup := func() {
		_ = stdinPipe.Close()
		done := make(chan struct{})
		go func() { _ = cmd.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			_ = cmd.Process.Kill()
			<-done
		}
	}
	return client, cleanup
}

// rpc sends one JSON-RPC request and reads the matching response. Uses
// MCP's newline-delimited framing — one JSON object per line.
func (c *mcpClient) rpc(t *testing.T, req map[string]any) map[string]any {
	t.Helper()
	body, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("marshal request: %v", err)
	}
	if _, err := c.stdin.Write(append(body, '\n')); err != nil {
		t.Fatalf("write stdin: %v", err)
	}
	// Read one line — may need to skip notifications (no id field) until
	// we find a response with the matching id.
	wantID := req["id"]
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		line, err := c.stdout.ReadString('\n')
		if err != nil {
			t.Fatalf("read stdout: %v", err)
		}
		var resp map[string]any
		if err := json.Unmarshal([]byte(line), &resp); err != nil {
			t.Fatalf("parse %q: %v", line, err)
		}
		if id, ok := resp["id"]; ok && fmt.Sprint(id) == fmt.Sprint(wantID) {
			return resp
		}
		// Otherwise: this was a notification or different-id response.
		// Loop and keep reading.
	}
	t.Fatalf("rpc timeout waiting for id %v", wantID)
	return nil
}

// notify sends a JSON-RPC notification (no id field, no response
// expected).
func (c *mcpClient) notify(t *testing.T, req map[string]any) {
	t.Helper()
	body, err := json.Marshal(req)
	if err != nil {
		t.Fatalf("marshal notify: %v", err)
	}
	if _, err := c.stdin.Write(append(body, '\n')); err != nil {
		t.Fatalf("write stdin: %v", err)
	}
}

// drainStderrAfter reaps stderr (best-effort) until the deadline. Used
// for error diagnostics — never blocks the happy path.
func drainStderrAfter(c *mcpClient, deadline time.Duration) string {
	ch := make(chan string, 1)
	go func() {
		buf := make([]byte, 4096)
		n, _ := c.stderr.Read(buf)
		ch <- string(buf[:n])
	}()
	select {
	case s := <-ch:
		return s
	case <-time.After(deadline):
		return ""
	}
}

func TestMCPServerInitializeAndListTools(t *testing.T) {
	if _, err := exec.LookPath("go"); err != nil {
		t.Skip("go toolchain not found")
	}
	_, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	bin := buildCodeiq(t)
	graphDir := seedEmptyGraph(t)
	rootDir := t.TempDir()

	client, cleanup := startCodeiqMCP(t, bin, graphDir, rootDir)
	defer cleanup()

	// 1. initialize handshake.
	init := client.rpc(t, map[string]any{
		"jsonrpc": "2.0",
		"id":      1,
		"method":  "initialize",
		"params": map[string]any{
			"protocolVersion": "2024-11-05",
			"capabilities":    map[string]any{},
			"clientInfo":      map[string]any{"name": "test", "version": "0"},
		},
	})
	result, ok := init["result"].(map[string]any)
	if !ok {
		t.Fatalf("initialize had no result: %v\nstderr=%s", init, drainStderrAfter(client, 500*time.Millisecond))
	}
	serverInfo, _ := result["serverInfo"].(map[string]any)
	if name, _ := serverInfo["name"].(string); name != "CODE MCP" {
		t.Fatalf("serverInfo.name = %v, want CODE MCP", serverInfo)
	}

	// 2. notifications/initialized — required before tool calls.
	client.notify(t, map[string]any{
		"jsonrpc": "2.0",
		"method":  "notifications/initialized",
	})

	// 3. tools/list — must return all 34 tools.
	listResp := client.rpc(t, map[string]any{
		"jsonrpc": "2.0",
		"id":      2,
		"method":  "tools/list",
	})
	listResult, ok := listResp["result"].(map[string]any)
	if !ok {
		t.Fatalf("tools/list had no result: %v", listResp)
	}
	tools, _ := listResult["tools"].([]any)
	if len(tools) != 10 {
		names := make([]string, 0, len(tools))
		for _, tl := range tools {
			if m, ok := tl.(map[string]any); ok {
				if n, ok := m["name"].(string); ok {
					names = append(names, n)
				}
			}
		}
		t.Fatalf("tools/list returned %d tools, want 10. names=%v", len(tools), names)
	}

	// 4. Spot-check representative tool names from the 10-tool surface.
	wantNames := []string{
		"graph_summary", "find_in_graph", "inspect_node",
		"trace_relationships", "analyze_impact", "topology_view",
		"run_cypher", "read_file", "generate_flow", "review_changes",
	}
	have := map[string]bool{}
	for _, tl := range tools {
		if m, ok := tl.(map[string]any); ok {
			if n, ok := m["name"].(string); ok {
				have[n] = true
			}
		}
	}
	for _, n := range wantNames {
		if !have[n] {
			t.Errorf("tools/list missing %s", n)
		}
	}

	// 5. Call graph_summary in capabilities mode — synchronous round
	// trip that exercises the consolidated tool dispatch path (which
	// internally delegates to the toolGetCapabilities builder).
	callResp := client.rpc(t, map[string]any{
		"jsonrpc": "2.0",
		"id":      3,
		"method":  "tools/call",
		"params": map[string]any{
			"name":      "graph_summary",
			"arguments": map[string]any{"mode": "capabilities"},
		},
	})
	callResult, ok := callResp["result"].(map[string]any)
	if !ok {
		t.Fatalf("tools/call graph_summary had no result: %v", callResp)
	}
	content, _ := callResult["content"].([]any)
	if len(content) == 0 {
		t.Fatalf("graph_summary returned empty content")
	}
	first, _ := content[0].(map[string]any)
	text, _ := first["text"].(string)
	var body map[string]any
	if err := json.Unmarshal([]byte(text), &body); err != nil {
		t.Fatalf("parse graph_summary body: %v\ntext=%s", err, text)
	}
	if _, hasMatrix := body["matrix"]; !hasMatrix {
		t.Fatalf("graph_summary capabilities body missing matrix: %v", body)
	}
}

