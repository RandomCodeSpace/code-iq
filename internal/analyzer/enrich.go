package analyzer

import (
	"fmt"
	"path/filepath"

	"github.com/randomcodespace/codeiq/internal/analyzer/linker"
	"github.com/randomcodespace/codeiq/internal/cache"
	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/graph"
	"github.com/randomcodespace/codeiq/internal/intelligence/extractor"
	extractorgolang "github.com/randomcodespace/codeiq/internal/intelligence/extractor/golang"
	extractorjava "github.com/randomcodespace/codeiq/internal/intelligence/extractor/java"
	extractorpython "github.com/randomcodespace/codeiq/internal/intelligence/extractor/python"
	extractortypescript "github.com/randomcodespace/codeiq/internal/intelligence/extractor/typescript"
	"github.com/randomcodespace/codeiq/internal/intelligence/lexical"
	"github.com/randomcodespace/codeiq/internal/model"
)

// EnrichOptions configures Enrich. The zero value is usable; GraphDir
// defaults to `<root>/.codeiq/graph/codeiq.kuzu` when empty.
type EnrichOptions struct {
	// GraphDir overrides the Kuzu output directory. When "", the default
	// `<root>/.codeiq/graph/codeiq.kuzu` is used.
	GraphDir string
	// StoreBufferPoolBytes caps Kuzu's buffer pool. Zero -> graph package
	// default (2 GiB).
	StoreBufferPoolBytes uint64
	// StoreCopyThreads caps Kuzu COPY FROM parallelism. Zero -> graph
	// package default (min(4, GOMAXPROCS)).
	StoreCopyThreads uint64
	// Force bypasses the incremental short-circuit. With Force=false (the
	// default), Enrich short-circuits when the cache and graph manifest
	// hashes match.
	Force bool
}

// EnrichSummary reports per-run counters from a successful Enrich.
//
// ShortCircuited is true when the cache and graph were already in sync
// and Enrich did no rebuild work. Nodes/Edges/Services report 0 in that
// case — callers should check ShortCircuited before treating zero as
// "empty graph".
//
// Mode is one of:
//   - "short-circuit" — cache + graph manifests matched; no work done.
//   - "full"          — fresh graph or non-matching manifest; full rebuild.
type EnrichSummary struct {
	Nodes          int
	Edges          int
	Services       int
	ShortCircuited bool
	Mode           string
}

// Enrich loads the SQLite cache for `root`, runs the linker / classifier /
// lexical / language-extractor / service-detector passes, bulk-loads the
// resulting graph into Kuzu, and creates the FTS-equivalent indexes. The
// returned summary reports total nodes / edges / service nodes after every
// pass has run.
//
// Incremental short-circuit: when a prior enrich wrote a manifest_hash to
// the graph and that hash matches the cache's current manifest, Enrich
// returns immediately with ShortCircuited=true. To force a full rebuild,
// pass Force=true.
//
// Re-runnable: when the graph already holds prior data (non-empty
// CodeNode table), Enrich calls Store.Reset() first so the subsequent
// BulkLoad doesn't collide on primary keys. This makes `enrich` safe to
// re-run after `index` picks up changes.
//
// Mirrors the `enrich` pipeline in Java (Analyzer.java + GraphStore.java).
// The pipeline order matches the Java side exactly:
//
//  1. Linkers (TopicLinker, EntityLinker, ModuleContainmentLinker)
//  2. LayerClassifier
//  3. LexicalEnricher (doc comments + config keys)
//  4. LanguageEnricher (Java, TypeScript, Python, Go extractors)
//  5. ServiceDetector (filesystem walk for build files)
//  6. graph.Store.BulkLoadNodes / BulkLoadEdges / CreateIndexes
//  7. graph.Store.WriteManifest(cache.ManifestHash())
//
// All steps are deterministic — repeated calls against the same cache + root
// produce identical Kuzu output.
func Enrich(root string, c *cache.Cache, opts EnrichOptions) (EnrichSummary, error) {
	if opts.GraphDir == "" {
		opts.GraphDir = filepath.Join(root, ".codeiq", "graph", "codeiq.kuzu")
	}

	store, err := graph.OpenWithOptions(opts.GraphDir, graph.OpenOptions{
		BufferPoolBytes: opts.StoreBufferPoolBytes,
		MaxThreads:      opts.StoreCopyThreads,
	})
	if err != nil {
		return EnrichSummary{}, fmt.Errorf("enrich: open graph: %w", err)
	}
	defer store.Close()
	if err := store.ApplySchema(); err != nil {
		return EnrichSummary{}, fmt.Errorf("enrich: apply schema: %w", err)
	}

	// Short-circuit when the cache and graph are already in sync.
	if !opts.Force {
		cacheManifest, mErr := c.ManifestHash()
		if mErr != nil {
			return EnrichSummary{}, fmt.Errorf("enrich: cache manifest: %w", mErr)
		}
		graphManifest, gErr := store.ReadManifest()
		if gErr != nil {
			return EnrichSummary{}, fmt.Errorf("enrich: graph manifest: %w", gErr)
		}
		if graphManifest != "" && cacheManifest == graphManifest {
			return EnrichSummary{ShortCircuited: true, Mode: "short-circuit"}, nil
		}
	}

	// Reset existing graph data so BulkLoad doesn't collide on PKs from a
	// prior run. Reset on a fresh graph is a cheap no-op.
	if err := store.Reset(); err != nil {
		return EnrichSummary{}, fmt.Errorf("enrich: reset: %w", err)
	}

	// Re-hydrate the graph from cache. GraphBuilder dedupes by node/edge ID and
	// produces a deterministic snapshot with dangling edges dropped.
	builder := NewGraphBuilder()
	err = c.IterateAll(func(r *cache.Entry) error {
		builder.Add(&detector.Result{Nodes: r.Nodes, Edges: r.Edges})
		return nil
	})
	if err != nil {
		return EnrichSummary{}, fmt.Errorf("enrich: iterate cache: %w", err)
	}
	snap := builder.Snapshot()
	nodes := snap.Nodes
	edges := snap.Edges

	// 1. Linkers — order matches Analyzer.java.
	// Plan §1.4 — Sorted() at the boundary makes the output independent of
	// any linker's internal iteration order.
	for _, l := range []linker.Linker{
		linker.NewTopicLinker(),
		linker.NewEntityLinker(),
		linker.NewModuleContainmentLinker(),
	} {
		r := l.Link(nodes, edges).Sorted()
		nodes = append(nodes, r.Nodes...)
		edges = append(edges, r.Edges...)
	}

	// 2. Layer classification — mutates nodes in place.
	(&LayerClassifier{}).Classify(nodes)

	// 3. Lexical enrichment — stamps lex_comment / lex_config_keys properties
	//    onto candidate nodes. Reads files from disk under root.
	lexical.NewEnricher().Enrich(nodes, root)

	// 4. Language extractors — stamp type hints, emit CALLS / IMPORTS edges.
	//    Registration is via init() in each extractor package; the orchestrator
	//    selects by file extension.
	en := extractor.NewEnricher(
		extractorjava.New(),
		extractortypescript.New(),
		extractorpython.New(),
		extractorgolang.New(),
	)
	en.Enrich(nodes, &edges, root)

	// 5. ServiceDetector — walk filesystem for build files, emit SERVICE nodes
	//    + CONTAINS edges. Mutates nodes' `service` property in place.
	sd := &ServiceDetector{}
	sres := sd.Detect(nodes, edges, filepath.Base(root), root)
	nodes = append(nodes, sres.Nodes...)
	edges = append(edges, sres.Edges...)

	// 6. Bulk-load Kuzu — nodes + edges + indexes.
	if err := store.BulkLoadNodes(nodes); err != nil {
		return EnrichSummary{}, fmt.Errorf("enrich: bulk load nodes: %w", err)
	}
	if err := store.BulkLoadEdges(edges); err != nil {
		return EnrichSummary{}, fmt.Errorf("enrich: bulk load edges: %w", err)
	}
	if err := store.CreateIndexes(); err != nil {
		return EnrichSummary{}, fmt.Errorf("enrich: create indexes: %w", err)
	}

	// 7. Write the manifest hash so the next run can short-circuit. Best-effort
	//    — a failed manifest write only forces the next run to re-do the work,
	//    which is annoying but not incorrect.
	if mh, mErr := c.ManifestHash(); mErr == nil {
		_ = store.WriteManifest(mh)
	}

	return EnrichSummary{
		Nodes:    len(nodes),
		Edges:    len(edges),
		Services: len(sres.Nodes),
		Mode:     "full",
	}, nil
}

// Touch the model.NodeService symbol so the package import stays meaningful
// even when callers don't reach for the constant directly — this gives the
// Java-side comment in EnrichSummary a referent and prevents accidental
// import pruning during goimports runs.
var _ = model.NodeService
