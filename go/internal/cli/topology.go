package cli

import (
	"fmt"
	"path/filepath"

	"github.com/randomcodespace/codeiq/go/internal/graph"
	"github.com/randomcodespace/codeiq/go/internal/query"
	"github.com/spf13/cobra"
)

func init() {
	registerSubcommand(newTopologyCommand)
}

// newTopologyCommand assembles the `topology` parent and its sub-views.
// The bare parent renders the full service map; sub-views surface specific
// analyses (service-detail / blast-radius / bottlenecks / circular / dead).
func newTopologyCommand() *cobra.Command {
	var graphDir string
	cmd := &cobra.Command{
		Use:   "topology [path]",
		Short: "Show the service topology map (services + cross-service connections).",
		Long: `Render the service topology: every SERVICE node ServiceDetector
synthesised plus every cross-service runtime edge (CALLS / PRODUCES /
CONSUMES / QUERIES / CONNECTS_TO / PUBLISHES / LISTENS / SENDS_TO /
RECEIVES_FROM / INVOKES_RMI / EXPORTS_RMI). The output carries
` + "`services`" + `, ` + "`connections`" + `, and ` + "`service_count`" + ` / ` + "`connection_count`" +
				` aggregates.

Subcommands narrow the view:
  service-detail <name>     endpoints / entities / guards / databases /
                             queues for one service.
  blast-radius <node-id>    nodes reachable from the given node.
  bottlenecks               services ordered by total connection count.
  circular                  cross-service dependency cycles.
  dead                      services with no incoming runtime edges.`,
		Example: `  # Bare topology map
  codeiq topology .

  # Detail for one service
  codeiq topology service-detail checkout-svc

  # Blast radius for a node
  codeiq topology blast-radius svc:checkout-svc --depth 3`,
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
			t := query.NewTopology(store)
			out, err := t.GetTopology()
			if err != nil {
				return err
			}
			return printOrdered(cmd.OutOrStdout(), out)
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	cmd.AddCommand(newTopologyServiceDetail())
	cmd.AddCommand(newTopologyBlastRadius())
	cmd.AddCommand(newTopologyBottlenecks())
	cmd.AddCommand(newTopologyCircular())
	cmd.AddCommand(newTopologyDead())
	cmd.AddCommand(newTopologyPath())
	return cmd
}

func newTopologyServiceDetail() *cobra.Command {
	var graphDir string
	cmd := &cobra.Command{
		Use:   "service-detail <name> [path]",
		Short: "Show endpoints / entities / guards / databases / queues for one service.",
		Long: `Render the detail object for the named SERVICE — endpoints,
entities, guards, databases, and queues that ServiceDetector pivoted under
this service via CONTAINS edges. Use ` + "`codeiq find services`" + ` to list
candidate names.`,
		Example: `  codeiq topology service-detail checkout-svc
  codeiq topology service-detail web-ui /repo
  codeiq topology service-detail notifier --graph-dir /tmp/scratch.kuzu`,
		Args: cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			name := args[0]
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
			t := query.NewTopology(store)
			out, err := t.ServiceDetail(name)
			if err != nil {
				return err
			}
			return printOrdered(cmd.OutOrStdout(), out)
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	return cmd
}

func newTopologyBlastRadius() *cobra.Command {
	var (
		graphDir string
		depth    int
	)
	cmd := &cobra.Command{
		Use:   "blast-radius <node-id> [path]",
		Short: "Show nodes reachable from the given node, up to --depth hops.",
		Long: `Render the blast-radius object for the given node — the set of
reachable nodes (via any runtime edge) and the services those nodes belong
to. Default depth is 5 hops; cap with ` + "`--depth`" + ` for tighter scopes.`,
		Example: `  codeiq topology blast-radius svc:checkout-svc
  codeiq topology blast-radius svc:checkout-svc --depth 3
  codeiq topology blast-radius method:com.foo.Bar#baz --depth 2`,
		Args: cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
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
			t := query.NewTopology(store)
			out, err := t.BlastRadius(id, depth)
			if err != nil {
				return err
			}
			return printOrdered(cmd.OutOrStdout(), out)
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	cmd.Flags().IntVar(&depth, "depth", 5,
		"Maximum traversal depth in hops (default: 5).")
	return cmd
}

func newTopologyBottlenecks() *cobra.Command {
	var graphDir string
	cmd := &cobra.Command{
		Use:   "bottlenecks [path]",
		Short: "List services ordered by total connection count (in + out).",
		Long: `Render services ranked by combined connection degree.
Services with zero connections are omitted. Sort order: total desc, then
service name asc — deterministic for diffing.`,
		Example: `  codeiq topology bottlenecks
  codeiq topology bottlenecks /repo
  codeiq topology bottlenecks --graph-dir /tmp/scratch.kuzu`,
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
			t := query.NewTopology(store)
			out, err := t.FindBottlenecks()
			if err != nil {
				return err
			}
			return printOrdered(cmd.OutOrStdout(), out)
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	return cmd
}

func newTopologyCircular() *cobra.Command {
	var graphDir string
	cmd := &cobra.Command{
		Use:   "circular [path]",
		Short: "Show cross-service dependency cycles.",
		Long: `Render the list of cross-service cycles — each entry is a
service-name slice with the same first and last element (closed loop).
Cycles are normalised so the smallest service name is at index 0 for
stable comparison across runs.`,
		Example: `  codeiq topology circular
  codeiq topology circular /repo
  codeiq topology circular --graph-dir /tmp/scratch.kuzu`,
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
			t := query.NewTopology(store)
			out, err := t.FindCircular()
			if err != nil {
				return err
			}
			return printOrdered(cmd.OutOrStdout(), out)
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	return cmd
}

func newTopologyDead() *cobra.Command {
	var graphDir string
	cmd := &cobra.Command{
		Use:   "dead [path]",
		Short: "List services with no incoming runtime edges.",
		Long: `Render services that have no incoming cross-service runtime
edge. Useful for spotting services nobody consumes (potential dead code,
or services with only outbound publishes). Excludes structural CONTAINS
edges by design.`,
		Example: `  codeiq topology dead
  codeiq topology dead /repo
  codeiq topology dead --graph-dir /tmp/scratch.kuzu`,
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
			t := query.NewTopology(store)
			out, err := t.FindDeadServices()
			if err != nil {
				return err
			}
			return printOrdered(cmd.OutOrStdout(), out)
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	return cmd
}

func newTopologyPath() *cobra.Command {
	var graphDir string
	cmd := &cobra.Command{
		Use:   "path <source> <target> [path]",
		Short: "Find the shortest cross-service path between two services.",
		Long: `Render the list of hops between two services via BFS over the
cross-service runtime adjacency. Each hop is ` + "`{from, to, type}`" + `; the
` + "`type`" + ` is the lowercased edge kind that linked the two hops in the
underlying graph.`,
		Example: `  codeiq topology path checkout-svc payments-svc
  codeiq topology path web-ui notifier /repo
  codeiq topology path checkout-svc fulfilment --graph-dir /tmp/scratch.kuzu`,
		Args: cobra.MinimumNArgs(2),
		RunE: func(cmd *cobra.Command, args []string) error {
			source := args[0]
			target := args[1]
			root, err := resolvePath(args[2:])
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
			t := query.NewTopology(store)
			out, err := t.FindPath(source, target)
			if err != nil {
				return err
			}
			return printOrdered(cmd.OutOrStdout(), out)
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	return cmd
}
