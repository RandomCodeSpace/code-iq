package analyzer

import (
	"fmt"
	"os"
	"runtime"
	"sync"
	"time"

	"github.com/randomcodespace/codeiq/go/internal/cache"
	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/parser"
)

// DefaultBatchSize matches the Java side's tuned default (CLAUDE.md gotcha).
const DefaultBatchSize = 500

// Options configures an Analyzer.
type Options struct {
	Cache     *cache.Cache
	Registry  *detector.Registry
	BatchSize int // defaults to DefaultBatchSize
	Workers   int // defaults to 2 * GOMAXPROCS
}

// Analyzer orchestrates the index pipeline.
type Analyzer struct {
	opts Options
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
type Stats struct {
	Files int
	Nodes int
	Edges int
}

// Run executes FileDiscovery → parse → detectors → GraphBuilder → cache writes
// and returns aggregate stats. Errors from individual file processing are
// logged to stderr but do not stop the run — partial output is better than no
// output (matches Java's per-file try/catch behaviour).
func (a *Analyzer) Run(root string) (Stats, error) {
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
		Files: len(files),
		Nodes: len(snap.Nodes),
		Edges: len(snap.Edges),
	}, nil
}

func (a *Analyzer) processFile(f DiscoveredFile, gb *GraphBuilder) error {
	content, err := os.ReadFile(f.AbsPath)
	if err != nil {
		return err
	}
	hash := cache.HashString(string(content))
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
		if err := a.opts.Cache.Put(entry); err != nil {
			return fmt.Errorf("cache put: %w", err)
		}
	}
	return nil
}
