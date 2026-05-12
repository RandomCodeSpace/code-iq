package cli

import (
	"context"
	"fmt"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/randomcodespace/codeiq/go/internal/buildinfo"
	"github.com/randomcodespace/codeiq/go/internal/graph"
	"github.com/randomcodespace/codeiq/go/internal/mcp"
	"github.com/randomcodespace/codeiq/go/internal/model"
	"github.com/randomcodespace/codeiq/go/internal/query"
	mcpsdk "github.com/modelcontextprotocol/go-sdk/mcp"
	"github.com/spf13/cobra"
)

func init() {
	registerSubcommand(newMCPCommand)
}

// newMCPCommand assembles `codeiq mcp` — runs the stdio MCP server that
// Claude Code spawns.
//
// The server opens the Kuzu graph read-only, wires every registered
// tool family (RegisterGraph today; topology/flow/intelligence land in
// follow-on phases), and runs the JSON-RPC protocol loop over stdin/
// stdout via the official Anthropic Go SDK.
//
// Stderr is the log channel — Claude Code surfaces stderr in its MCP
// server log panel. The CLI does not write to stdout outside of the
// JSON-RPC stream because doing so would corrupt the protocol.
func newMCPCommand() *cobra.Command {
	var (
		graphDir     string
		maxResults   int
		maxDepth     int
		queryTimeout time.Duration
	)
	cmd := &cobra.Command{
		Use:   "mcp [path]",
		Short: "Run the stdio MCP server (Claude Code spawns this).",
		Long: `Run a JSON-RPC MCP server over stdin / stdout. Claude Code
launches this subcommand when the project's .mcp.json registers ` + "`codeiq`" + `
as an MCP server.

Prerequisites: ` + "`codeiq index`" + ` and ` + "`codeiq enrich`" + ` must have been run
against the target repository so the Kuzu graph at .codeiq/graph/ is
populated. The Kuzu store is opened read-only; mutation keywords in
` + "`run_cypher`" + ` are rejected at the gate.

Stderr is the log channel — Claude Code surfaces stderr in its MCP server
log panel. Do not write anything to stdout outside of the JSON-RPC stream
or the protocol will break.

To register with Claude Code, add to .mcp.json at the repo root:

  {
    "mcpServers": {
      "code-mcp": {
        "command": "codeiq",
        "args": ["mcp"]
      }
    }
  }`,
		Example: `  codeiq mcp                                # foreground stdio server
  codeiq mcp 2> /tmp/codeiq-mcp.log         # capture stderr
  codeiq mcp --graph-dir /tmp/scratch.kuzu  # alternate graph location`,
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			root, err := resolvePath(args)
			if err != nil {
				return err
			}
			gdir := graphDir
			if gdir == "" {
				gdir = filepath.Join(root, ".codeiq", "graph", "codeiq.kuzu")
			}
			store, err := graph.OpenReadOnly(gdir, queryTimeout)
			if err != nil {
				return fmt.Errorf("open graph %s: %w", gdir, err)
			}
			defer store.Close()

			deps := &mcp.Deps{
				Store: store,
				Query: query.NewService(store),
				Stats: query.NewStatsServiceFromStore(func() ([]*model.CodeNode, []*model.CodeEdge, error) {
					nodes, err := store.LoadAllNodes()
					if err != nil {
						return nil, nil, err
					}
					edges, err := store.LoadAllEdges()
					if err != nil {
						return nodes, nil, err
					}
					return nodes, edges, nil
				}),
				Topology:   query.NewTopology(store),
				RootPath:   root,
				MaxResults: maxResults,
				MaxDepth:   maxDepth,
			}
			srv, err := mcp.NewServer(mcp.ServerOptions{
				Name:    "CODE MCP",
				Version: buildinfo.Version,
			})
			if err != nil {
				return err
			}
			if err := registerAllTools(srv, deps); err != nil {
				return err
			}

			ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
			defer cancel()
			return srv.Serve(ctx, &mcpsdk.StdioTransport{})
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	cmd.Flags().IntVar(&maxResults, "max-results", 500,
		"Cap on caller-supplied result counts in tools that page over rows.")
	cmd.Flags().IntVar(&maxDepth, "max-depth", 10,
		"Cap on caller-supplied traversal depths (ego graph / trace impact / blast radius).")
	cmd.Flags().DurationVar(&queryTimeout, "query-timeout", graph.DefaultQueryTimeout,
		"Per-Cypher-query wall-clock timeout (default: 30s).")
	return cmd
}

// registerAllTools wires every tool family available in the current build
// onto srv. Today only RegisterGraph is in place — RegisterTopology,
// RegisterFlow, and RegisterIntelligence land as their sections of phase 3
// complete. Each Register call is best-effort: a missing function (the
// parallel agent's still-in-flight package) means that tool family is
// absent from `tools/list` until the function lands; the server still
// starts.
func registerAllTools(srv *mcp.Server, d *mcp.Deps) error {
	if err := mcp.RegisterGraph(srv, d); err != nil {
		return fmt.Errorf("register graph tools: %w", err)
	}
	for _, hook := range optionalRegisterHooks {
		if hook == nil {
			continue
		}
		if err := hook(srv, d); err != nil {
			return err
		}
	}
	return nil
}

// optionalRegisterHooks is the registration hook list for tool families
// whose package may or may not be linked into the binary yet. Each phase-3
// section appends to this slice from its own file (see mcp_hooks.go for
// the parallel-agent-friendly registration pattern). Today the slice is
// empty — graph tools are unconditional via registerAllTools above.
var optionalRegisterHooks []func(*mcp.Server, *mcp.Deps) error
