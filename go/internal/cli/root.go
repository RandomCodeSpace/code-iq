// Package cli wires Cobra commands. The exported NewRootCommand() builder is
// testable from package _test files; Execute() is the main-entry shim.
package cli

import (
	"fmt"
	"os"

	"github.com/spf13/cobra"
)

// Global flag state, populated by Cobra at parse time.
var (
	flagConfig  string
	flagNoColor bool
	flagJSON    bool
	flagVerbose int
	flagShowVer bool // --version on root
)

// NewRootCommand builds the codeiq root command and all subcommands. Each
// subcommand registers itself via init() in this package.
func NewRootCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "codeiq",
		Short: "Deterministic code knowledge graph (CLI + stdio MCP).",
		Long: `codeiq -- deterministic code knowledge graph (CLI + stdio MCP)

codeiq scans a codebase, builds a deterministic knowledge graph from the
detected nodes and edges, and exposes it to humans via a CLI and to LLM
agents via a stdio MCP server. No AI, no external APIs -- pure static
analysis.

Typical workflow:
  codeiq index   .   # scan files, populate SQLite cache
  codeiq enrich  .   # load cache into Kuzu graph store (phase 2)
  codeiq mcp         # run stdio MCP server (phase 3)
`,
		Example: `  codeiq index .                  # Scan the current directory.
  codeiq enrich .                 # Build the graph from the cache.
  codeiq mcp                      # Run the MCP server (stdio).
  codeiq stats --json             # Stats as JSON.`,
		RunE: func(cmd *cobra.Command, args []string) error {
			if flagShowVer {
				return printVersion(cmd.OutOrStdout(), flagJSON)
			}
			// No args + no --version => print help.
			return cmd.Help()
		},
		SilenceUsage:               true,
		SuggestionsMinimumDistance: 1,
	}
	pf := cmd.PersistentFlags()
	pf.StringVar(&flagConfig, "config", "", "Path to codeiq.yml (default: ./codeiq.yml then ~/.codeiq/config.yml).")
	pf.BoolVar(&flagNoColor, "no-color", false, "Disable ANSI color in output.")
	pf.BoolVar(&flagJSON, "json", false, "Emit JSON output where applicable.")
	pf.CountVarP(&flagVerbose, "verbose", "v", "Verbose logging (repeatable: -v / -vv / -vvv).")

	// --version on root, equivalent to `codeiq version`.
	cmd.Flags().BoolVar(&flagShowVer, "version", false, "Show version and exit (alias of `codeiq version`).")

	// Register subcommands.
	for _, sub := range subcommands() {
		cmd.AddCommand(sub)
	}
	return cmd
}

// Execute is the main entry point — runs the root command and returns the
// exit code (0 success, 1 usage error, 2 runtime error).
func Execute() int {
	cmd := NewRootCommand()
	if err := cmd.Execute(); err != nil {
		// Cobra already printed the error; choose exit code based on type.
		// usageError == 1, runtime/other == 2.
		if _, ok := err.(*usageError); ok {
			return 1
		}
		fmt.Fprintln(os.Stderr, "Error:", err)
		return 2
	}
	return 0
}

// usageError marks errors that are user-input problems (missing arg, unknown
// flag). RunE returns this so exit code is 1, not 2.
type usageError struct{ msg string }

func (u *usageError) Error() string { return u.msg }

// newUsageError is the typed constructor.
func newUsageError(format string, args ...any) error {
	return &usageError{msg: fmt.Sprintf(format, args...)}
}

// subcommandRegistry is mutated by subcommand init() funcs. Order doesn't
// matter — Cobra sorts by Name() in help output.
var subcommandRegistry []func() *cobra.Command

func subcommands() []*cobra.Command {
	out := make([]*cobra.Command, 0, len(subcommandRegistry))
	for _, fn := range subcommandRegistry {
		out = append(out, fn())
	}
	return out
}

// registerSubcommand appends a subcommand builder. Each subcommand file calls
// this from init().
func registerSubcommand(fn func() *cobra.Command) {
	subcommandRegistry = append(subcommandRegistry, fn)
}
