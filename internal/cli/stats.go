package cli

import (
	"fmt"
	"path/filepath"

	"github.com/randomcodespace/codeiq/internal/graph"
	"github.com/randomcodespace/codeiq/internal/model"
	"github.com/randomcodespace/codeiq/internal/query"
	"github.com/spf13/cobra"
)

func init() {
	registerSubcommand(func() *cobra.Command {
		var (
			graphDir string
			asJSON   bool
			category string
		)
		cmd := &cobra.Command{
			Use:   "stats [path]",
			Short: "Show categorized statistics from the analyzed graph.",
			Long: `Show counts and breakdowns from a graph previously built by ` + "`enrich`" + `.

Seven categories are surfaced: graph (node/edge/file totals), languages,
frameworks, infra (databases, messaging, cloud), connections (REST by
method, gRPC, websocket, producer/consumer edge counts), auth, and
architecture (classes / interfaces / methods / modules). Use ` + "`--category`" +
				` to focus on a single section and ` + "`--json`" + ` to pipe into other tools.

The default rendering is JSON because the output already carries
deterministic key order via OrderedMap; the ` + "`--json`" + ` flag is therefore
a no-op today but kept for forward compatibility with a future tabular
rendering.`,
			Example: `  # Tabular summary
  codeiq stats .

  # Just the infrastructure category as JSON
  codeiq stats . --category infra --json

  # Pipe into jq
  codeiq stats . --json | jq '.languages'`,
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
				store, err := graph.Open(gdir)
				if err != nil {
					return fmt.Errorf("open graph %s: %w", gdir, err)
				}
				defer store.Close()

				svc := query.NewStatsServiceFromStore(
					func() ([]*model.CodeNode, []*model.CodeEdge, error) {
						ns, e := store.LoadAllNodes()
						if e != nil {
							return nil, nil, e
						}
						es, e := store.LoadAllEdges()
						if e != nil {
							return nil, nil, e
						}
						return ns, es, nil
					},
				)
				var out any
				if category != "" {
					out = svc.ComputeCategory(category)
				} else {
					out = svc.ComputeStats()
				}
				if err := svc.LoadErr(); err != nil {
					return fmt.Errorf("load graph: %w", err)
				}
				_ = asJSON // both modes use JSON for now
				return printOrdered(cmd.OutOrStdout(), out)
			},
		}
		cmd.Flags().StringVar(&graphDir, "graph-dir", "",
			"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
		cmd.Flags().BoolVar(&asJSON, "json", false,
			"Emit JSON output (currently always JSON; reserved for a future tabular renderer).")
		cmd.Flags().StringVar(&category, "category", "",
			"Show only one category (graph|languages|frameworks|infra|connections|auth|architecture).")
		return cmd
	})
}
