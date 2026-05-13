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
	"github.com/randomcodespace/codeiq/go/internal/flow"
	"github.com/randomcodespace/codeiq/go/internal/graph"
	iqquery "github.com/randomcodespace/codeiq/go/internal/intelligence/query"
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
				Topology:     query.NewTopology(store),
				Flow:         flow.NewEngine(store),
				QueryPlanner: iqquery.NewPlanner(iqquery.CapabilityMatrixFor),
				// Evidence assembler + ArtifactMeta are wired by the
				// intelligence/evidence loader once it lands the on-disk
				// manifest format. Until then get_evidence_pack and
				// get_artifact_metadata return the legacy `{"error":
				// "...unavailable. Run 'enrich' first."}` envelope which
				// matches the Java contract for the "no metadata yet"
				// path. RegisterIntelligence registers the tools either
				// way so tools/list is stable.
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

// registerAllTools wires every user-facing MCP tool family onto srv:
// graph (run_cypher + read_file = 2) + flow (generate_flow = 1) +
// consolidated (6 mode-driven) + review_changes = 10 tools.
//
// The narrow graph / topology / intelligence tool implementations are
// retained inside the mcp package because the consolidated tools
// delegate to them at the Go-API level, but they are no longer
// registered as user-facing MCP tools (greenfield project, no
// external consumers — the back-compat surface was dropped).
//
// `optionalRegisterHooks` remains for forward-compat with new tool
// families that may land in later phases without re-touching this
// function.
func registerAllTools(srv *mcp.Server, d *mcp.Deps) error {
	if err := mcp.RegisterGraphUserFacing(srv, d); err != nil {
		return fmt.Errorf("register graph tools: %w", err)
	}
	if err := mcp.RegisterFlow(srv, d); err != nil {
		return fmt.Errorf("register flow tools: %w", err)
	}
	if err := mcp.RegisterConsolidated(srv, d); err != nil {
		return fmt.Errorf("register consolidated tools: %w", err)
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
// whose package may or may not be linked into the binary yet. Reserved
// for future tool-family extensions; the four core families
// (graph / topology / flow / intelligence) are wired unconditionally
// above.
var optionalRegisterHooks []func(*mcp.Server, *mcp.Deps) error
