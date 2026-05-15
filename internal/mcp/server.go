package mcp

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"sync"

	mcpsdk "github.com/modelcontextprotocol/go-sdk/mcp"
)

// ServerOptions configures the codeiq MCP server.
type ServerOptions struct {
	// Name is the protocol-level server name advertised in `initialize`.
	// Matches the Java side `spring.ai.mcp.server.name` value: "CODE MCP".
	Name string
	// Version of the codeiq binary (build-info Version string).
	Version string
	// ResolvedRoot is the project root the server already resolved at boot
	// (via projectroot.Resolve). When the connected MCP client also exposes
	// roots via ListRoots, we compare the two and log a warning to stderr
	// if they disagree — but we do not swap the open Kuzu store mid-flight
	// (that's a larger refactor; tracked as a follow-up). Empty string
	// disables the ListRoots check.
	ResolvedRoot string
}

// Server is the stdio MCP server. One per `codeiq mcp` process. Tools
// are registered via Register, then Serve is called with a transport
// (StdioTransport in production, NewInMemoryTransports in tests).
type Server struct {
	opts     ServerOptions
	registry *Registry
	mu       sync.Mutex
}

// NewServer constructs an unstarted Server. Tools are registered separately
// via Register before calling Serve. Returns an error when required
// options are missing — currently only Name is mandatory.
func NewServer(opts ServerOptions) (*Server, error) {
	if opts.Name == "" {
		return nil, fmt.Errorf("mcp: ServerOptions.Name is required")
	}
	if opts.Version == "" {
		opts.Version = "dev"
	}
	return &Server{
		opts:     opts,
		registry: NewRegistry(),
	}, nil
}

// Register adds a Tool to the registry. Must be called before Serve.
// Concurrency-safe — the registry mutex serializes adds.
func (s *Server) Register(t Tool) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.registry.Add(t)
}

// Registry exposes the underlying Registry for read-only inspection (used
// by tests like TestRegisterGraphRegistersAllTwentyTools). Callers must
// not mutate the registry after Serve has been called.
func (s *Server) Registry() *Registry { return s.registry }

// Serve runs the MCP protocol loop on the supplied transport. Blocks
// until the transport closes or ctx is cancelled. The transport choice
// determines stdin/stdout vs in-memory vs HTTP behaviour; see the
// package doc for the v0.8.0 SDK quirk re: StdioTransport's
// hard-coded os.Stdin/os.Stdout binding.
func (s *Server) Serve(ctx context.Context, transport mcpsdk.Transport) error {
	impl := &mcpsdk.Implementation{
		Name:    s.opts.Name,
		Version: s.opts.Version,
	}
	// Wire an InitializedHandler so we can ask the client for its workspace
	// roots once the session is initialised. compareRootsWithClient runs
	// best-effort: ListRoots may be unsupported by the client, in which case
	// we silently keep our boot-time resolution. Mismatches go to stderr as
	// a warning but do not swap the open Kuzu handle (out of scope for this
	// PR; tracked as a follow-up).
	sdkOpts := &mcpsdk.ServerOptions{}
	if s.opts.ResolvedRoot != "" {
		expected := s.opts.ResolvedRoot
		sdkOpts.InitializedHandler = func(ctx context.Context, req *mcpsdk.InitializedRequest) {
			compareRootsWithClient(ctx, req.Session, expected)
		}
	}
	sdkSrv := mcpsdk.NewServer(impl, sdkOpts)

	s.mu.Lock()
	for _, t := range s.registry.All() {
		tool, handler := t.asSDKTool()
		sdkSrv.AddTool(tool, handler)
	}
	s.mu.Unlock()

	return sdkSrv.Run(ctx, transport)
}

// compareRootsWithClient calls session.ListRoots and emits a stderr warning
// when the client's roots do not include the boot-resolved root. Best-effort:
// errors are swallowed (the client may not advertise roots capability).
//
// The path comparison normalises with filepath.Abs+Clean to absorb trailing
// slashes and symlink-equivalent prefixes. The `file://` URI shape is also
// supported because some MCP clients (Claude Code) emit roots as file URIs.
func compareRootsWithClient(ctx context.Context, ss *mcpsdk.ServerSession, expected string) {
	expectedAbs, err := filepath.Abs(expected)
	if err != nil {
		return
	}
	expectedAbs = filepath.Clean(expectedAbs)

	res, err := ss.ListRoots(ctx, nil)
	if err != nil || res == nil || len(res.Roots) == 0 {
		return // client didn't expose roots — keep our boot resolution
	}
	var clientRoots []string
	matched := false
	for _, r := range res.Roots {
		p := uriToPath(r.URI)
		abs, err := filepath.Abs(p)
		if err != nil {
			continue
		}
		abs = filepath.Clean(abs)
		clientRoots = append(clientRoots, abs)
		if abs == expectedAbs {
			matched = true
			break
		}
	}
	if !matched {
		fmt.Fprintf(os.Stderr,
			"codeiq mcp: WARNING — boot-resolved project root %q is not among "+
				"the client's workspace roots %v. The MCP server will keep using %q. "+
				"To switch, restart codeiq with that path as the positional arg or "+
				"set CODEIQ_PROJECT_ROOT.\n",
			expectedAbs, clientRoots, expectedAbs)
	}
}

// uriToPath unwraps `file://<path>` URIs into bare filesystem paths. MCP
// roots are declared as URIs per the spec; clients that send a bare path are
// also accepted as a kindness.
func uriToPath(uri string) string {
	const prefix = "file://"
	if len(uri) >= len(prefix) && uri[:len(prefix)] == prefix {
		return uri[len(prefix):]
	}
	return uri
}
