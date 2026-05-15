package analyzer

import (
	"fmt"
	"os"
	"runtime"
	"sync"
	"sync/atomic"
	"time"

	"github.com/randomcodespace/codeiq/internal/cache"
	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/parser"
)

// DefaultBatchSize matches the Java side's tuned default (CLAUDE.md gotcha).
const DefaultBatchSize = 500

// Options configures an Analyzer.
type Options struct {
	Cache     *cache.Cache
	Registry  *detector.Registry
	BatchSize int  // defaults to DefaultBatchSize
	Workers   int  // defaults to 2 * GOMAXPROCS
	Force     bool // bypass cache early-exit; re-parse every file
}

// Analyzer orchestrates the index pipeline.
type Analyzer struct {
	opts    Options
	counter runCounter
}

type runCounter struct {
	cacheHits atomic.Int64
}

// NewAnalyzer returns an analyzer wired to opts.
func NewAnalyzer(opts Options) *Analyzer {
	if opts.BatchSize <= 0 {
		opts.BatchSize = DefaultBatchSize
	}
	if opts.Workers <= 0 {
		opts.Workers = runtime.GOMAXPROCS(0) * 2
	}
	if opts.Registry == nil {
		opts.Registry = detector.Default
	}
	return &Analyzer{opts: opts}
}

// Stats reports per-run counts.
//
// Plan §1.5 — DedupedNodes/DedupedEdges/DroppedEdges expose dedup activity
// so operators can see "graph collapsed 312 duplicate nodes, dropped 14
// phantom edges" — the visibility is what makes "meaningful" diagnosable.
//
// Added/Modified/Deleted/Unchanged/CacheHits are incremental counters,
// zero on full `--force` runs.
type Stats struct {
	Files        int
	Nodes        int
	Edges        int
	DedupedNodes int
	DedupedEdges int
	DroppedEdges int
	Added        int
	Modified     int
	Deleted      int
	Unchanged    int
	CacheHits    int
}

// Run executes FileDiscovery → parse → detectors → GraphBuilder → cache writes
// and returns aggregate stats. Errors from individual file processing are
// logged to stderr but do not stop the run — partial output is better than no
// output (matches Java's per-file try/catch behaviour).
//
// On non-Force runs with a cache present, Run first runs Diff() to classify
// files, purges cache rows for deleted files, then proceeds. processFile
// skips parse+detect for UNCHANGED files (content_hash hit in cache).
func (a *Analyzer) Run(root string) (Stats, error) {
	a.counter.cacheHits.Store(0)

	var d Delta
	if a.opts.Cache != nil && !a.opts.Force {
		var err error
		d, err = a.Diff(root)
		if err != nil {
			return Stats{}, err
		}
		for _, path := range d.Deleted {
			if err := a.opts.Cache.PurgeByPath(path); err != nil {
				fmt.Fprintf(os.Stderr, "codeiq: purge %s: %v\n", path, err)
			}
		}
	}

	disc := NewFileDiscovery()
	files, err := disc.Discover(root)
	if err != nil {
		return Stats{}, fmt.Errorf("file discovery: %w", err)
	}
	gb := NewGraphBuilder()

	// Bounded worker pool.
	type job struct {
		f DiscoveredFile
	}
	jobs := make(chan job)
	var wg sync.WaitGroup
	for i := 0; i < a.opts.Workers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for j := range jobs {
				if err := a.processFile(j.f, gb); err != nil {
					fmt.Fprintf(os.Stderr, "codeiq: %s: %v\n", j.f.RelPath, err)
				}
			}
		}()
	}
	for _, f := range files {
		jobs <- job{f: f}
	}
	close(jobs)
	wg.Wait()

	snap := gb.Snapshot()
	return Stats{
		Files:        len(files),
		Nodes:        len(snap.Nodes),
		Edges:        len(snap.Edges),
		DedupedNodes: snap.DedupedNodes,
		DedupedEdges: snap.DedupedEdges,
		DroppedEdges: snap.DroppedEdges,
		Added:        len(d.Added),
		Modified:     len(d.Modified),
		Deleted:      len(d.Deleted),
		Unchanged:    len(d.Unchanged),
		CacheHits:    int(a.counter.cacheHits.Load()),
	}, nil
}

func (a *Analyzer) processFile(f DiscoveredFile, gb *GraphBuilder) error {
	content, err := os.ReadFile(f.AbsPath)
	if err != nil {
		return err
	}
	hash := cache.HashString(string(content))

	// Fast path: cache hit. Reuse the previous emissions; skip parse+detect.
	if a.opts.Cache != nil && !a.opts.Force && a.opts.Cache.Has(hash) {
		entry, gerr := a.opts.Cache.Get(hash)
		if gerr == nil && entry != nil {
			gb.Add(&detector.Result{Nodes: entry.Nodes, Edges: entry.Edges})
			a.counter.cacheHits.Add(1)
			return nil
		}
		// Has() true but Get() failed — pathological. Fall through to re-parse.
	}

	tree, err := parser.Parse(f.Language, content)
	if err != nil {
		// Continue with regex-only detectors when the parser bails — matches
		// Java behaviour for non-fatal parse errors.
		tree = nil
	}
	if tree != nil {
		defer tree.Close()
	}
	parsed, _ := parser.ParseStructured(f.Language, content)
	ctx := &detector.Context{
		FilePath:   f.RelPath,
		Language:   f.Language.String(),
		Content:    string(content),
		Tree:       tree,
		ParsedData: parsed,
	}

	entry := &cache.Entry{
		ContentHash: hash,
		Path:        f.RelPath,
		Language:    f.Language.String(),
		ParsedAt:    time.Now().UTC().Format(time.RFC3339),
	}
	for _, d := range a.opts.Registry.For(f.Language.String()) {
		r := d.Detect(ctx)
		if r == nil {
			continue
		}
		gb.Add(r)
		entry.Nodes = append(entry.Nodes, r.Nodes...)
		entry.Edges = append(entry.Edges, r.Edges...)
	}
	if a.opts.Cache != nil {
		// MODIFIED files: purge prior (path, old_hash) row so a single path
		// never has two cache entries.
		_ = a.opts.Cache.PurgeByPath(f.RelPath)
		if err := a.opts.Cache.Put(entry); err != nil {
			return fmt.Errorf("cache put: %w", err)
		}
	}
	return nil
}
