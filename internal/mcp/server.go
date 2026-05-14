package mcp

import (
	"context"
	"fmt"
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
	sdkSrv := mcpsdk.NewServer(impl, nil)

	s.mu.Lock()
	for _, t := range s.registry.All() {
		tool, handler := t.asSDKTool()
		sdkSrv.AddTool(tool, handler)
	}
	s.mu.Unlock()

	return sdkSrv.Run(ctx, transport)
}
