package cli

import (
	"fmt"
	"path/filepath"

	"github.com/randomcodespace/codeiq/internal/graph"
	"github.com/spf13/cobra"
)

func init() {
	registerSubcommand(newFindCommand)
}

// findKindSpec is one row of the finder-subcommand table: a sub-name, the
// NodeKind it filters on, plus the short / long doc strings.
type findKindSpec struct {
	name, kind, short, long string
}

// findKindSpecs is the table of preset finders. Order is preserved in `--help`
// output; new finders go to the bottom.
var findKindSpecs = []findKindSpec{
	{
		"endpoints", "endpoint",
		"List ENDPOINT nodes from the graph.",
		`Return all REST / gRPC / messaging endpoint nodes from the enriched
graph, paginated. Endpoints are produced by detectors such as Spring REST,
Flask / FastAPI / Django routes, Express controllers, gRPC server stubs, and
the Kafka @KafkaListener family.`,
	},
	{
		"guards", "guard",
		"List GUARD nodes (auth filters, route guards) from the graph.",
		`Return all GUARD nodes from the enriched graph. Guards represent auth
filters / route guards / middleware-style gatekeepers — Spring Security
filters, FastAPI Depends, Angular route guards, etc.`,
	},
	{
		"entities", "entity",
		"List ENTITY nodes (JPA / ORM entities) from the graph.",
		`Return all persisted ENTITY nodes from the enriched graph. Entities
are produced by ORM detectors (JPA, EF Core, Django models, SQLAlchemy,
Sequelize, TypeORM, GORM, ...).`,
	},
	{
		"topics", "topic",
		"List TOPIC nodes (Kafka, RabbitMQ, Redis Streams, ...) from the graph.",
		`Return all messaging TOPIC nodes from the enriched graph. Topics are
emitted by messaging detectors — Kafka @KafkaListener / @SendTo, Spring
Cloud Stream bindings, NestJS @MessagePattern, Rust lapin queues, etc.`,
	},
	{
		"queues", "queue",
		"List QUEUE nodes from the graph.",
		`Return all messaging QUEUE nodes from the enriched graph. Queues are
detected separately from topics — JMS / SQS / RabbitMQ direct queues live
here, while pub-sub topics live under ` + "`find topics`" + `.`,
	},
	{
		"services", "service",
		"List SERVICE nodes (module/service boundaries) from the graph.",
		`Return all SERVICE nodes from the enriched graph. SERVICE nodes are
synthesised by ServiceDetector from build files (pom.xml, package.json,
Cargo.toml, ...) and represent module / service boundaries.`,
	},
	{
		"databases", "database_connection",
		"List DATABASE_CONNECTION nodes from the graph.",
		`Return all DATABASE_CONNECTION nodes from the enriched graph. These
are detected from JDBC URLs, application-yml datasource blocks, EF Core
DbContext configurations, Sequelize / TypeORM connection options, ...`,
	},
	{
		"components", "component",
		"List COMPONENT nodes (frontend components) from the graph.",
		`Return all frontend COMPONENT nodes from the enriched graph —
React / Vue / Angular / Svelte component declarations detected by the
frontend extractor family.`,
	},
}

// newFindCommand assembles the `find` parent and one finder subcommand per
// entry in findKindSpecs.
func newFindCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "find <what> [path]",
		Short: "Preset finders for common node kinds (endpoints, guards, entities, ...).",
		Long: `Preset finders return paginated lists of nodes of a given kind from
the enriched graph. Higher-level than ` + "`codeiq query`" + `, which operates on
individual node ids; ` + "`codeiq find`" + ` returns whole categories.

Each finder accepts ` + "`--limit`" + ` / ` + "`--offset`" + ` for paging and produces
tab-separated ` + "`id\\tlabel`" + ` rows ordered by id.`,
		Example: `  codeiq find endpoints
  codeiq find entities --limit 50
  codeiq find services /repo --graph-dir /tmp/scratch.kuzu`,
		RunE: func(c *cobra.Command, _ []string) error { return c.Help() },
	}
	for _, spec := range findKindSpecs {
		cmd.AddCommand(newFindKindCommand(spec))
	}
	return cmd
}

// newFindKindCommand returns one finder subcommand for the given spec. The
// shared body resolves the path / graph-dir, opens the store, calls
// FindByKindPaginated, and prints `id\tlabel` rows.
func newFindKindCommand(spec findKindSpec) *cobra.Command {
	var (
		graphDir string
		limit    int
		offset   int
	)
	cmd := &cobra.Command{
		Use:   spec.name + " [path]",
		Short: spec.short,
		Long:  spec.long,
		Example: fmt.Sprintf(`  codeiq find %s
  codeiq find %s --limit 200
  codeiq find %s /repo`, spec.name, spec.name, spec.name),
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
			nodes, err := store.FindByKindPaginated(spec.kind, offset, limit)
			if err != nil {
				return err
			}
			for _, n := range nodes {
				fmt.Fprintf(cmd.OutOrStdout(), "%s\t%s\n", n.ID, n.Label)
			}
			return nil
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	cmd.Flags().IntVar(&limit, "limit", 100,
		"Maximum number of rows to return (default: 100).")
	cmd.Flags().IntVar(&offset, "offset", 0,
		"Skip the first N rows (default: 0).")
	return cmd
}
