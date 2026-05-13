package cli

import (
	"encoding/json"
	"fmt"
	"io"
	"path/filepath"
	"sort"
	"strings"
	"text/tabwriter"
	"time"

	"github.com/randomcodespace/codeiq/go/internal/graph"
	"github.com/spf13/cobra"
)

func init() {
	registerSubcommand(newCypherCommand)
}

// newCypherCommand assembles `codeiq cypher` — the actually-implemented Go
// port of `cypher` (the Java side is a stub since commit 81b645c). Runs a
// read-only Cypher query against the Kuzu store and prints rows as JSON
// (default) or a column-aligned table.
//
// Per the read-only contract, mutation keywords (CREATE / DELETE / SET /
// MERGE / DROP / CALL non-readonly-procs) are rejected before execution
// by the OpenReadOnly + MutationKeyword gate in internal/graph.
func newCypherCommand() *cobra.Command {
	var (
		graphDir     string
		asTable      bool
		maxResults   int
		queryTimeout time.Duration
	)
	cmd := &cobra.Command{
		Use:   "cypher <query> [path]",
		Short: "Execute a raw read-only Cypher query against the Kuzu graph.",
		Long: `Execute a single read-only Cypher query against the Kuzu graph and
print the result rows to stdout as JSON (default) or a column-aligned table.

The Kuzu store is opened read-only. Mutation keywords (CREATE, DELETE,
SET, MERGE, REMOVE, DETACH, DROP, FOREACH, LOAD CSV, COPY, and CALL of
non-readonly procedures) are rejected before execution. Result rows are
capped at --max-results; the response carries a "truncated" flag when
the cap is hit so the caller can re-run with a tighter query.

Note: the Java side ` + "`cypher`" + ` command has been a stub since commit
81b645c — the Go port actually wires this through to graph.CypherRows().`,
		Example: `  codeiq cypher "MATCH (n) RETURN count(n) AS c"
  codeiq cypher "MATCH (n:CodeNode) RETURN n.label LIMIT 5" --table
  codeiq cypher 'MATCH (n) RETURN n.kind, count(n) ORDER BY count(n) DESC' --max-results 50`,
		Args: cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			query := args[0]
			root, err := resolvePath(args[1:])
			if err != nil {
				return err
			}
			gdir := graphDir
			if gdir == "" {
				gdir = filepath.Join(root, ".codeiq", "graph", "codeiq.kuzu")
			}
			// Cheap early-out: surface the blocked keyword before opening
			// Kuzu so the read-only gate's error message reaches the user
			// quickly. The graph layer will re-check after open.
			if kw := graph.MutationKeyword(query); kw != "" {
				return fmt.Errorf("cypher: read-only queries only (blocked keyword: %s)", kw)
			}
			store, err := graph.OpenReadOnly(gdir, queryTimeout)
			if err != nil {
				return fmt.Errorf("open graph %s: %w", gdir, err)
			}
			defer store.Close()

			rows, truncated, err := store.CypherRows(query, nil, maxResults)
			if err != nil {
				return err
			}
			if asTable {
				return printCypherTable(cmd.OutOrStdout(), rows)
			}
			out := map[string]any{
				"rows":  rows,
				"count": len(rows),
			}
			if truncated {
				out["truncated"] = true
				out["max_results"] = maxResults
			}
			enc := json.NewEncoder(cmd.OutOrStdout())
			enc.SetIndent("", "  ")
			return enc.Encode(out)
		},
	}
	cmd.Flags().StringVar(&graphDir, "graph-dir", "",
		"Path to the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
	cmd.Flags().BoolVar(&asTable, "table", false,
		"Render rows as a column-aligned table instead of JSON.")
	cmd.Flags().IntVar(&maxResults, "max-results", 500,
		"Maximum number of result rows to return (default: 500).")
	cmd.Flags().DurationVar(&queryTimeout, "query-timeout", graph.DefaultQueryTimeout,
		"Per-query wall-clock timeout (default: 30s).")
	return cmd
}

// printCypherTable renders rows as a column-aligned table using
// text/tabwriter. Column order is taken from the first row; missing cells
// in subsequent rows render as empty strings. Empty input is a no-op.
func printCypherTable(w io.Writer, rows []map[string]any) error {
	if len(rows) == 0 {
		return nil
	}
	// Stable column order: the union of all row keys, sorted.
	keySet := make(map[string]struct{})
	for _, r := range rows {
		for k := range r {
			keySet[k] = struct{}{}
		}
	}
	cols := make([]string, 0, len(keySet))
	for k := range keySet {
		cols = append(cols, k)
	}
	sort.Strings(cols)

	tw := tabwriter.NewWriter(w, 0, 0, 2, ' ', 0)
	fmt.Fprintln(tw, strings.Join(cols, "\t"))
	for _, r := range rows {
		cells := make([]string, len(cols))
		for i, c := range cols {
			cells[i] = fmt.Sprintf("%v", r[c])
		}
		fmt.Fprintln(tw, strings.Join(cells, "\t"))
	}
	return tw.Flush()
}
