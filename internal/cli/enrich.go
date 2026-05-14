package cli

import (
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"runtime/pprof"

	"github.com/randomcodespace/codeiq/internal/analyzer"
	"github.com/randomcodespace/codeiq/internal/cache"
	"github.com/spf13/cobra"
)

func init() {
	registerSubcommand(func() *cobra.Command {
		var (
			graphDir       string
			memProfile     string
			maxBufferPool  int64
			copyThreads    int
		)
		cmd := &cobra.Command{
			Use:   "enrich [path]",
			Short: "Load the SQLite cache into Kuzu and run linkers, classifiers, intelligence.",
			Long: `Enrich the analysis cache into a Kuzu graph store.

Reads the SQLite cache previously written by ` + "`codeiq index`" + ` and runs
the in-memory enrichment passes -- linkers (TopicLinker, EntityLinker,
ModuleContainmentLinker), the layer classifier, the lexical enricher
(doc comments + config keys), per-language extractors (Java, TypeScript,
Python, Go), and the filesystem-driven service detector. The resulting
node + edge set is bulk-loaded into a Kuzu database at
` + "`.codeiq/graph/codeiq.kuzu/`" + ` and indexed for fast read queries.

This is the second step of the pipeline ` + "`index -> enrich -> mcp`" + `.
After enrich, read-side commands (` + "`stats`, `query`, `find`, `topology`" + `)
become available and the stdio MCP server can serve clients.`,
			Example: `  # Enrich the current directory using the cache written by index
  codeiq enrich .

  # Override the graph output directory (handy for staging migrations)
  codeiq enrich --graph-dir /tmp/scratch.kuzu /repo

  # Typical pipeline
  codeiq index /repo && codeiq enrich /repo && codeiq stats /repo`,
			Args: cobra.MaximumNArgs(1),
			RunE: func(cmd *cobra.Command, args []string) error {
				root, err := resolvePath(args)
				if err != nil {
					return err
				}
				cachePath := filepath.Join(root, ".codeiq", "cache", "codeiq.sqlite")
				c, err := cache.Open(cachePath)
				if err != nil {
					return fmt.Errorf("open cache %s: %w", cachePath, err)
				}
				defer c.Close()
				opts := analyzer.EnrichOptions{GraphDir: graphDir}
				if maxBufferPool > 0 {
					opts.StoreBufferPoolBytes = uint64(maxBufferPool)
				}
				if copyThreads > 0 {
					opts.StoreCopyThreads = uint64(copyThreads)
				}
				summary, err := analyzer.Enrich(root, c, opts)
				if err != nil {
					return err
				}
				if memProfile != "" {
					runtime.GC()
					f, ferr := os.Create(memProfile)
					if ferr != nil {
						return fmt.Errorf("create mem profile: %w", ferr)
					}
					defer f.Close()
					if perr := pprof.WriteHeapProfile(f); perr != nil {
						return fmt.Errorf("write mem profile: %w", perr)
					}
					fmt.Fprintf(cmd.ErrOrStderr(), "heap profile written to %s\n", memProfile)
				}
				fmt.Fprintf(cmd.OutOrStdout(),
					"enrich complete: %d nodes, %d edges, %d services\n",
					summary.Nodes, summary.Edges, summary.Services)
				return nil
			},
		}
		cmd.Flags().StringVar(&graphDir, "graph-dir", "",
			"Output directory for the Kuzu graph store (default: <path>/.codeiq/graph/codeiq.kuzu).")
		cmd.Flags().StringVar(&memProfile, "memprofile", "",
			"Write a heap profile to this path after enrich completes. For OOM debugging — use with /usr/bin/time -v.")
		cmd.Flags().Int64Var(&maxBufferPool, "max-buffer-pool", 0,
			"Cap Kuzu BufferPoolSize in bytes (default: 2 GiB; 0 means default).")
		cmd.Flags().IntVar(&copyThreads, "copy-threads", 0,
			"Cap Kuzu COPY FROM parallelism (default: min(4, GOMAXPROCS); 0 means default).")
		return cmd
	})
}
