package cli

import (
	"context"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/randomcodespace/codeiq/internal/flow"
	"github.com/randomcodespace/codeiq/internal/graph"
	"github.com/spf13/cobra"
)

func init() {
	registerSubcommand(newFlowCommand)
}

// newFlowCommand assembles `codeiq flow` — generates an architecture flow
// diagram for one of the five canonical views.
func newFlowCommand() *cobra.Command {
	var (
		graphDir     string
		format       string
		outPath      string
		queryTimeout time.Duration
	)
	cmd := &cobra.Command{
		Use:   "flow <view> [path]",
		Short: "Generate an architecture flow diagram (overview / ci / deploy / runtime / auth).",
		Long: `Generate an architecture flow diagram for the analyzed codebase.

Five views ship out of the box:
  overview   The high-level system view (CI + Infra + App + Security).
  ci         CI/CD pipeline detail (workflows, jobs, triggers).
  deploy     Deployment topology (K8s, Docker, Terraform).
  runtime    Runtime architecture grouped by layer.
  auth       Auth / security view with protection coverage.

Output formats: json (default), mermaid, dot, yaml. Use --out to write to
a file instead of stdout. The renderer is deterministic — nodes within
each subgraph and edges are sorted by ID before emission.`,
		Example: `  codeiq flow overview
  codeiq flow runtime --format mermaid > runtime.mmd
  codeiq flow auth --format dot --out auth.dot
  codeiq flow deploy --format yaml /repo`,
		Args: cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			view := args[0]
			if !flow.IsKnownView(view) {
				return newUsageError(
					"unknown view %q; valid: overview, ci, deploy, runtime, auth", view)
			}
			format = strings.ToLower(strings.TrimSpace(format))
			root, err := resolvePath(args[1:])
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

			engine := flow.NewEngine(store)
			diag, err := engine.Generate(context.Background(), flow.View(view))
			if err != nil {
				return err
			}
			rendered, err := flow.Render(diag, format)
			if err != nil {
				return err
			}
			return writeFlowOutput(cmd.OutOrStdout(), rendered, outPath)
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	cmd.Flags().StringVar(&format, "format", "json",
		"Output format: json, mermaid, dot, yaml.")
	cmd.Flags().StringVar(&outPath, "out", "",
		"Write the rendered diagram to this file instead of stdout.")
	cmd.Flags().DurationVar(&queryTimeout, "query-timeout", graph.DefaultQueryTimeout,
		"Per-query wall-clock timeout (default: 30s).")
	return cmd
}

// writeFlowOutput emits content to outPath (when non-empty) or to w.
// Always terminates with a trailing newline if the content lacks one.
func writeFlowOutput(w io.Writer, content, outPath string) error {
	if !strings.HasSuffix(content, "\n") {
		content += "\n"
	}
	if outPath == "" {
		_, err := io.WriteString(w, content)
		return err
	}
	return os.WriteFile(outPath, []byte(content), 0o644)
}
