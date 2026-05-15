package cli

import (
	"encoding/json"
	"fmt"
	"path/filepath"

	"github.com/randomcodespace/codeiq/internal/analyzer"
	"github.com/randomcodespace/codeiq/internal/cache"
	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/spf13/cobra"
)

func init() {
	registerSubcommand(func() *cobra.Command {
		var cachePath string
		cmd := &cobra.Command{
			Use:   "diff [path]",
			Short: "Show the cache vs disk delta without touching the graph.",
			Long: `Walk the project at [path] and classify each file against the
SQLite analysis cache:

  - Added     -- on disk, not in cache
  - Modified  -- path in cache but content hash differs from disk
  - Deleted   -- in cache, missing from disk
  - Unchanged -- path + content hash match cache exactly

Useful for previewing what an incremental ` + "`codeiq index`" + ` /
` + "`codeiq enrich`" + ` run would do. The cache is not modified.`,
			Example: `  codeiq diff .
  codeiq diff /path/to/repo
  codeiq diff /path/to/repo --cache-path /tmp/scratch.sqlite`,
			Args: cobra.MaximumNArgs(1),
			RunE: func(cmd *cobra.Command, args []string) error {
				root, err := resolvePath(args)
				if err != nil {
					return err
				}
				cp := cachePath
				if cp == "" {
					cp = filepath.Join(root, ".codeiq", "cache", "codeiq.sqlite")
				}
				c, err := cache.Open(cp)
				if err != nil {
					return fmt.Errorf("open cache %s: %w", cp, err)
				}
				defer c.Close()
				a := analyzer.NewAnalyzer(analyzer.Options{
					Cache:    c,
					Registry: detector.Default,
				})
				d, err := a.Diff(root)
				if err != nil {
					return err
				}
				out := map[string]any{
					"added":     stringsOrEmpty(d.Added),
					"modified":  stringsOrEmpty(d.Modified),
					"deleted":   stringsOrEmpty(d.Deleted),
					"unchanged": stringsOrEmpty(d.Unchanged),
					"counts": map[string]int{
						"added":     len(d.Added),
						"modified":  len(d.Modified),
						"deleted":   len(d.Deleted),
						"unchanged": len(d.Unchanged),
					},
				}
				enc := json.NewEncoder(cmd.OutOrStdout())
				enc.SetIndent("", "  ")
				return enc.Encode(out)
			},
		}
		cmd.Flags().StringVar(&cachePath, "cache-path", "",
			"Path to the cache file (default: <path>/.codeiq/cache/codeiq.sqlite).")
		return cmd
	})
}

// stringsOrEmpty replaces a nil slice with an empty one so JSON output is
// `[]` instead of `null` for empty buckets.
func stringsOrEmpty(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}
