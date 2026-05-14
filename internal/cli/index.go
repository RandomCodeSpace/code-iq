package cli

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/randomcodespace/codeiq/internal/analyzer"
	"github.com/randomcodespace/codeiq/internal/cache"
	"github.com/randomcodespace/codeiq/internal/detector"

	// Blank imports register all phase-1 detectors with detector.Default.
	_ "github.com/randomcodespace/codeiq/internal/detector/generic"
	_ "github.com/randomcodespace/codeiq/internal/detector/jvm/java"
	_ "github.com/randomcodespace/codeiq/internal/detector/python"

	"github.com/spf13/cobra"
)

func init() {
	registerSubcommand(func() *cobra.Command {
		var (
			batchSize int
			workers   int
		)
		cmd := &cobra.Command{
			Use:   "index [path]",
			Short: "Scan a codebase into the analysis cache (write path).",
			Long: `Scan the source tree at [path] and write detector results into
the SQLite analysis cache at <path>/.codeiq/cache/codeiq.sqlite. The cache is
keyed by SHA-256 file content hash so subsequent runs reuse cached results
for unchanged files. After indexing, run "codeiq enrich" to load the cache
into the Kuzu graph store (phase 2).

Phase 1 ships 5 detectors -- Spring REST controllers, JPA entities, Django
models, Flask routes, and a generic-imports detector. Languages covered:
Java and Python.`,
			Example: `  codeiq index .
  codeiq index /path/to/repo --batch-size 1000 --workers 8
  codeiq index .
  # -> Files: 12  Nodes: 47  Edges: 23  Cache: ./.codeiq/cache/codeiq.sqlite`,
			Args: cobra.MaximumNArgs(1),
			RunE: func(cmd *cobra.Command, args []string) error {
				path := "."
				if len(args) == 1 {
					path = args[0]
				}
				abs, err := filepath.Abs(path)
				if err != nil {
					return err
				}
				if st, err := os.Stat(abs); err != nil || !st.IsDir() {
					return newUsageError("path %q is not a directory", abs)
				}
				cacheDir := filepath.Join(abs, ".codeiq", "cache")
				if err := os.MkdirAll(cacheDir, 0755); err != nil {
					return fmt.Errorf("mkdir cache: %w", err)
				}
				dbPath := filepath.Join(cacheDir, "codeiq.sqlite")
				c, err := cache.Open(dbPath)
				if err != nil {
					return err
				}
				defer c.Close()

				a := analyzer.NewAnalyzer(analyzer.Options{
					Cache:     c,
					Registry:  detector.Default,
					BatchSize: batchSize,
					Workers:   workers,
				})
				stats, err := a.Run(abs)
				if err != nil {
					return err
				}
				fmt.Fprintf(cmd.OutOrStdout(),
					"Files: %d  Nodes: %d  Edges: %d  Cache: %s\n",
					stats.Files, stats.Nodes, stats.Edges, dbPath)
				if stats.DedupedNodes > 0 || stats.DedupedEdges > 0 || stats.DroppedEdges > 0 {
					fmt.Fprintf(cmd.OutOrStdout(),
						"Deduped: %d nodes, %d edges  Dropped: %d phantom edges\n",
						stats.DedupedNodes, stats.DedupedEdges, stats.DroppedEdges)
				}
				return nil
			},
		}
		cmd.Flags().IntVar(&batchSize, "batch-size", 500,
			"Number of files processed per batch (default: 500).")
		cmd.Flags().IntVarP(&workers, "workers", "w", 0,
			"Worker goroutine count (default: 2 * GOMAXPROCS).")
		return cmd
	})
}
