package cli

import (
	"fmt"
	"io"
	"path/filepath"

	"github.com/randomcodespace/codeiq/internal/graph"
	"github.com/randomcodespace/codeiq/internal/model"
	"github.com/randomcodespace/codeiq/internal/query"
	"github.com/spf13/cobra"
)

func init() {
	registerSubcommand(newQueryCommand)
}

// newQueryCommand assembles the `query` parent and its five preset
// subcommands. Each child shares the same path-resolution / graph-open
// boilerplate via runQueryFinder so the per-subcommand bodies stay readable.
func newQueryCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "query <subcommand>",
		Short: "Run preset graph queries (consumers, producers, callers, dependencies, dependents).",
		Long: `Preset query commands that issue targeted Cypher against the
enriched graph store. Each subcommand takes a node id and prints the
matching neighbour set; combine with ` + "`codeiq find`" + ` for higher-level
finders that return whole categories (endpoints, entities, ...).

The output is tab-separated ` + "`id\\tkind\\tlabel`" + ` per row — easy to pipe
into ` + "`awk`" + ` / ` + "`cut`" + ` and stable across runs because the underlying Cypher
ORDER BYs the projected id column.`,
		Example: `  codeiq query consumers svc:checkout
  codeiq query callers method:com.foo.Bar#baz
  codeiq query dependencies svc:fulfilment`,
		RunE: func(c *cobra.Command, _ []string) error { return c.Help() },
	}
	cmd.AddCommand(newQueryConsumers())
	cmd.AddCommand(newQueryProducers())
	cmd.AddCommand(newQueryCallers())
	cmd.AddCommand(newQueryDependencies())
	cmd.AddCommand(newQueryDependents())
	return cmd
}

// finderFn matches the signature of every query.Service.FindXxx method —
// take a node id, return a node slice.
type finderFn func(svc *query.Service, id string) ([]*model.CodeNode, error)

// runQueryFinder is the shared body for every preset query subcommand. It
// resolves the path, opens the graph, runs `fn` against the supplied node
// id, and prints tab-separated `id\tkind\tlabel` rows.
func runQueryFinder(w io.Writer, args []string, graphDir string, fn finderFn) error {
	if len(args) < 1 {
		return newUsageError("missing node-id argument")
	}
	id := args[0]
	root, err := resolvePath(args[1:])
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
	svc := query.NewService(store)
	nodes, err := fn(svc, id)
	if err != nil {
		return err
	}
	for _, n := range nodes {
		fmt.Fprintf(w, "%s\t%s\t%s\n", n.ID, n.Kind, n.Label)
	}
	return nil
}

func newQueryConsumers() *cobra.Command {
	var graphDir string
	cmd := &cobra.Command{
		Use:   "consumers <node-id> [path]",
		Short: "Show nodes that consume the given node.",
		Long: `Return the set of nodes reachable to the given node via
consume-direction runtime edges (CONSUMES, LISTENS). Excludes structural
edges (CONTAINS, DEFINES, IMPORTS) and build-time DEPENDS_ON.

The argument is a graph node id (e.g. ` + "`svc:checkout`" + ` or
` + "`endpoint:/api/users:GET`" + `); see ` + "`codeiq find`" + ` for finders that
return whole categories.`,
		Example: `  codeiq query consumers svc:checkout
  codeiq query consumers svc:checkout /repo
  codeiq query consumers svc:checkout --graph-dir /tmp/scratch.kuzu`,
		Args: cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runQueryFinder(cmd.OutOrStdout(), args, graphDir,
				func(s *query.Service, id string) ([]*model.CodeNode, error) {
					return s.FindConsumers(id)
				})
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	return cmd
}

func newQueryProducers() *cobra.Command {
	var graphDir string
	cmd := &cobra.Command{
		Use:   "producers <node-id> [path]",
		Short: "Show nodes that produce / publish to the given node.",
		Long: `Return the set of nodes that produce or publish to the given
target, via PRODUCES and PUBLISHES edges. Typical use: locate every code
path writing to a topic / queue node, or every controller method that
emits a domain event.`,
		Example: `  codeiq query producers topic:users.created
  codeiq query producers topic:users.created /repo
  codeiq query producers topic:users.created --graph-dir /tmp/scratch.kuzu`,
		Args: cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runQueryFinder(cmd.OutOrStdout(), args, graphDir,
				func(s *query.Service, id string) ([]*model.CodeNode, error) {
					return s.FindProducers(id)
				})
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	return cmd
}

func newQueryCallers() *cobra.Command {
	var graphDir string
	cmd := &cobra.Command{
		Use:   "callers <node-id> [path]",
		Short: "Show methods that call the given method (CALLS-direction).",
		Long: `Return the set of nodes that CALL the given target via CALLS
edges. Use this to trace the upstream invocation chain to a method or
endpoint. Pair with ` + "`codeiq query consumers`" + ` for the runtime-edge
counterpart (consume vs. invoke).`,
		Example: `  codeiq query callers method:com.foo.Bar#baz
  codeiq query callers method:com.foo.Bar#baz /repo
  codeiq query callers method:com.foo.Bar#baz --graph-dir /tmp/scratch.kuzu`,
		Args: cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runQueryFinder(cmd.OutOrStdout(), args, graphDir,
				func(s *query.Service, id string) ([]*model.CodeNode, error) {
					return s.FindCallers(id)
				})
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	return cmd
}

func newQueryDependencies() *cobra.Command {
	var graphDir string
	cmd := &cobra.Command{
		Use:   "dependencies <node-id> [path]",
		Short: "Show DEPENDS_ON children of the given node (outgoing).",
		Long: `Return the set of nodes that the given source DEPENDS_ON via
build-time / declarative edges. Symmetric to ` + "`codeiq query dependents`" + ` —
where dependencies looks downstream, dependents looks upstream.`,
		Example: `  codeiq query dependencies svc:fulfilment
  codeiq query dependencies svc:fulfilment /repo
  codeiq query dependencies svc:fulfilment --graph-dir /tmp/scratch.kuzu`,
		Args: cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runQueryFinder(cmd.OutOrStdout(), args, graphDir,
				func(s *query.Service, id string) ([]*model.CodeNode, error) {
					return s.FindDependencies(id)
				})
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	return cmd
}

func newQueryDependents() *cobra.Command {
	var graphDir string
	cmd := &cobra.Command{
		Use:   "dependents <node-id> [path]",
		Short: "Show nodes that DEPEND_ON the given node (incoming).",
		Long: `Return the set of nodes that DEPENDS_ON the given target via
build-time / declarative edges. Symmetric to
` + "`codeiq query dependencies`" + ` — handy for blast-radius style "what
breaks if I remove X" questions.`,
		Example: `  codeiq query dependents svc:fulfilment
  codeiq query dependents svc:fulfilment /repo
  codeiq query dependents svc:fulfilment --graph-dir /tmp/scratch.kuzu`,
		Args: cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			return runQueryFinder(cmd.OutOrStdout(), args, graphDir,
				func(s *query.Service, id string) ([]*model.CodeNode, error) {
					return s.FindDependents(id)
				})
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	return cmd
}
