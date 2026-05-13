package cli

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"io"
	"path/filepath"
	"strings"
	"text/tabwriter"

	"github.com/randomcodespace/codeiq/go/internal/cache"
	"github.com/spf13/cobra"
)

func init() {
	registerSubcommand(newCacheCommand)
}

// newCacheCommand assembles `codeiq cache` and its four subcommands —
// `info`, `list`, `inspect`, `clear`. The parent prints help with no args.
func newCacheCommand() *cobra.Command {
	cmd := &cobra.Command{
		Use:   "cache <action>",
		Short: "Inspect or manage the analysis cache (SQLite).",
		Long: `Inspect or manage the SQLite analysis cache that ` + "`codeiq index`" + `
writes to. The cache is keyed by SHA-256 content hash so subsequent runs
reuse detector results for unchanged files.

Subcommands:
  info       Print row counts, version, and on-disk size.
  list       Page through cached file entries.
  inspect    Print the deserialised nodes + edges for one entry.
  clear      Wipe every file / node / edge row (preserves the version).`,
		Example: `  codeiq cache info
  codeiq cache list --limit 20
  codeiq cache inspect path/to/UserController.java
  codeiq cache clear --yes`,
		RunE: func(c *cobra.Command, _ []string) error { return c.Help() },
	}
	cmd.AddCommand(newCacheInfoCommand())
	cmd.AddCommand(newCacheListCommand())
	cmd.AddCommand(newCacheInspectCommand())
	cmd.AddCommand(newCacheClearCommand())
	return cmd
}

func newCacheInfoCommand() *cobra.Command {
	var cachePath string
	cmd := &cobra.Command{
		Use:   "info [path]",
		Short: "Print summary stats about the analysis cache.",
		Long: `Print row counts, cache version, and on-disk size of the
SQLite analysis cache. Use ` + "`--cache-path`" + ` to point at a different
file (default: <path>/.codeiq/cache/codeiq.sqlite).`,
		Example: `  codeiq cache info
  codeiq cache info /repo
  codeiq cache info --cache-path /tmp/scratch.sqlite`,
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			dbPath, err := resolveCachePath(args, cachePath)
			if err != nil {
				return err
			}
			c, err := cache.Open(dbPath)
			if err != nil {
				return fmt.Errorf("open cache %s: %w", dbPath, err)
			}
			defer c.Close()
			stats, err := c.Stats()
			if err != nil {
				return err
			}
			stats.SizeBytes = cache.FileSize(dbPath)
			out := map[string]any{
				"path":        dbPath,
				"size_bytes":  stats.SizeBytes,
				"version":     stats.Version,
				"file_count":  stats.FileCount,
				"node_count":  stats.NodeCount,
				"edge_count":  stats.EdgeCount,
			}
			return jsonOut(cmd.OutOrStdout(), out)
		},
	}
	cmd.Flags().StringVar(&cachePath, "cache-path", "",
		"Path to the SQLite cache file (default: <path>/.codeiq/cache/codeiq.sqlite).")
	return cmd
}

func newCacheListCommand() *cobra.Command {
	var (
		cachePath string
		limit     int
		offset    int
		asJSON    bool
	)
	cmd := &cobra.Command{
		Use:   "list [path]",
		Short: "Page through cached file entries.",
		Long: `Page through cached file entries ordered by path. Default
output is a tab-aligned table; pass ` + "`--json`" + ` for a machine-parseable
JSON array.`,
		Example: `  codeiq cache list
  codeiq cache list --limit 20
  codeiq cache list --json --limit 5`,
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			dbPath, err := resolveCachePath(args, cachePath)
			if err != nil {
				return err
			}
			c, err := cache.Open(dbPath)
			if err != nil {
				return fmt.Errorf("open cache %s: %w", dbPath, err)
			}
			defer c.Close()
			entries, err := c.List(limit, offset)
			if err != nil {
				return err
			}
			if asJSON {
				return jsonOut(cmd.OutOrStdout(), entries)
			}
			return printCacheListTable(cmd.OutOrStdout(), entries)
		},
	}
	cmd.Flags().StringVar(&cachePath, "cache-path", "",
		"Path to the SQLite cache file (default: <path>/.codeiq/cache/codeiq.sqlite).")
	cmd.Flags().IntVar(&limit, "limit", 100,
		"Maximum number of entries to return (default: 100, 0 for unlimited).")
	cmd.Flags().IntVar(&offset, "offset", 0,
		"Skip the first N entries (default: 0).")
	cmd.Flags().BoolVar(&asJSON, "json", false,
		"Emit entries as a JSON array instead of a table.")
	return cmd
}

func newCacheInspectCommand() *cobra.Command {
	var cachePath string
	cmd := &cobra.Command{
		Use:   "inspect <hash|path> [path]",
		Short: "Print the deserialised nodes/edges for one cached entry.",
		Long: `Print the cached entry for the given content hash or file
path. The lookup tries (in order): exact content hash, exact file path,
then path-suffix match — useful when you only remember the relative path.`,
		Example: `  codeiq cache inspect path/to/User.java
  codeiq cache inspect abc123def456...
  codeiq cache inspect User.java /repo`,
		Args: cobra.MinimumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			query := args[0]
			dbPath, err := resolveCachePath(args[1:], cachePath)
			if err != nil {
				return err
			}
			c, err := cache.Open(dbPath)
			if err != nil {
				return fmt.Errorf("open cache %s: %w", dbPath, err)
			}
			defer c.Close()
			entry, err := c.LookupByHashOrPath(query)
			if err != nil {
				if err == sql.ErrNoRows {
					return fmt.Errorf("no cache entry matched %q", query)
				}
				return err
			}
			return jsonOut(cmd.OutOrStdout(), entry)
		},
	}
	cmd.Flags().StringVar(&cachePath, "cache-path", "",
		"Path to the SQLite cache file (default: <path>/.codeiq/cache/codeiq.sqlite).")
	return cmd
}

func newCacheClearCommand() *cobra.Command {
	var (
		cachePath string
		yes       bool
	)
	cmd := &cobra.Command{
		Use:   "clear [path]",
		Short: "Wipe every cached file / node / edge entry.",
		Long: `Remove every cached row from files / nodes / edges /
analysis_runs. The cache version is preserved so the next ` + "`codeiq index`" + `
does not trigger a version-mismatch rebuild prompt.

This is a destructive operation. ` + "`--yes`" + ` is required to confirm —
no interactive prompt; CI-friendly.`,
		Example: `  codeiq cache clear --yes
  codeiq cache clear --yes /repo
  codeiq cache clear --yes --cache-path /tmp/scratch.sqlite`,
		Args: cobra.MaximumNArgs(1),
		RunE: func(cmd *cobra.Command, args []string) error {
			if !yes {
				return newUsageError("cache clear is destructive; re-run with --yes to confirm")
			}
			dbPath, err := resolveCachePath(args, cachePath)
			if err != nil {
				return err
			}
			c, err := cache.Open(dbPath)
			if err != nil {
				return fmt.Errorf("open cache %s: %w", dbPath, err)
			}
			defer c.Close()
			n, err := c.Clear()
			if err != nil {
				return err
			}
			fmt.Fprintf(cmd.OutOrStdout(), "cleared %d cache entries from %s\n", n, dbPath)
			return nil
		},
	}
	cmd.Flags().StringVar(&cachePath, "cache-path", "",
		"Path to the SQLite cache file (default: <path>/.codeiq/cache/codeiq.sqlite).")
	cmd.Flags().BoolVar(&yes, "yes", false,
		"Confirm the destructive operation (required for clear to proceed).")
	return cmd
}

// --- helpers ---

// resolveCachePath returns the SQLite cache path. Explicit --cache-path
// wins; otherwise the standard <root>/.codeiq/cache/codeiq.sqlite.
func resolveCachePath(args []string, override string) (string, error) {
	if override != "" {
		return override, nil
	}
	root, err := resolvePath(args)
	if err != nil {
		return "", err
	}
	return filepath.Join(root, ".codeiq", "cache", "codeiq.sqlite"), nil
}

// jsonOut writes v as indented JSON to w with a trailing newline.
func jsonOut(w io.Writer, v any) error {
	enc := json.NewEncoder(w)
	enc.SetIndent("", "  ")
	return enc.Encode(v)
}

// printCacheListTable renders cache entries as a column-aligned table.
func printCacheListTable(w io.Writer, entries []cache.ListEntry) error {
	tw := tabwriter.NewWriter(w, 0, 0, 2, ' ', 0)
	fmt.Fprintln(tw, "PATH\tLANGUAGE\tNODES\tEDGES\tHASH")
	for _, e := range entries {
		fmt.Fprintf(tw, "%s\t%s\t%d\t%d\t%s\n",
			e.Path, e.Language, e.NodeCount, e.EdgeCount, truncateHash(e.ContentHash))
	}
	return tw.Flush()
}

// truncateHash returns the first 12 chars of a hash for compact rendering.
func truncateHash(h string) string {
	if len(h) <= 12 {
		return h
	}
	return strings.ToLower(h[:12])
}

