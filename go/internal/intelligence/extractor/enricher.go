package extractor

import (
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"

	"github.com/randomcodespace/codeiq/go/internal/model"
	"github.com/randomcodespace/codeiq/go/internal/parser"
)

// Enricher orchestrates per-language extractors over a node list. Mirrors
// LanguageEnricher.java. The zero value is unusable; use NewEnricher.
type Enricher struct {
	extractors map[string]LanguageExtractor
}

// NewEnricher returns an enricher that dispatches each registered extractor
// against nodes whose file extension maps (via DetectLanguage) to the
// extractor's Language(). Registering two extractors for the same language is
// last-wins.
func NewEnricher(exts ...LanguageExtractor) *Enricher {
	m := make(map[string]LanguageExtractor, len(exts))
	for _, e := range exts {
		m[e.Language()] = e
	}
	return &Enricher{extractors: m}
}

// Enrich runs all registered extractors against the in-memory node list,
// appending new edges to *edges and stamping type-hint properties onto the
// nodes themselves. Source files are read at most once across all nodes
// sharing a file path. Per-file work runs on a goroutine per file; results
// merge back in sorted-file order so the output is deterministic regardless
// of scheduler timing.
//
// `root` is the project root that node.FilePath is relative to. Files outside
// the root (failed reads, missing files) are silently skipped — extractors
// are best-effort.
func (en *Enricher) Enrich(nodes []*model.CodeNode, edges *[]*model.CodeEdge, root string) {
	if len(en.extractors) == 0 || len(nodes) == 0 {
		return
	}
	registry := buildRegistry(nodes)

	// Group nodes by file path. Skip nodes whose file_type marks them as
	// non-source (test, generated, minified, etc.) — matches Java behaviour.
	byFile := map[string][]*model.CodeNode{}
	for _, n := range nodes {
		if n == nil || n.FilePath == "" {
			continue
		}
		if ft, ok := n.Properties["file_type"].(string); ok {
			switch ft {
			case "test", "generated", "minified", "binary", "text", "filtered":
				continue
			}
		}
		byFile[n.FilePath] = append(byFile[n.FilePath], n)
	}

	// Deterministic file iteration order.
	paths := make([]string, 0, len(byFile))
	for p := range byFile {
		paths = append(paths, p)
	}
	sort.Strings(paths)

	type task struct {
		path string
		ext  LanguageExtractor
		ns   []*model.CodeNode
	}
	tasks := make([]task, 0, len(paths))
	for _, p := range paths {
		lang := DetectLanguage(p)
		if lang == "" {
			continue
		}
		if alias, ok := languageAliases[lang]; ok {
			lang = alias
		}
		ex, ok := en.extractors[lang]
		if !ok {
			continue
		}
		tasks = append(tasks, task{path: p, ext: ex, ns: byFile[p]})
	}
	if len(tasks) == 0 {
		return
	}

	// Run per-file work concurrently; collect into indexed slots so the
	// final concat order matches `paths` (sorted) — deterministic output.
	out := make([][]*model.CodeEdge, len(tasks))
	var wg sync.WaitGroup
	for i, t := range tasks {
		wg.Add(1)
		go func(i int, t task) {
			defer wg.Done()
			full := filepath.Join(root, t.path)
			raw, err := os.ReadFile(full)
			if err != nil {
				return
			}
			content := string(raw)
			if isLikelyMinified(t.path, content) {
				return
			}
			ctx := Context{
				FilePath: t.path,
				Language: t.ext.Language(),
				Content:  content,
				Registry: registry,
			}
			// Parse once per file; reuse the tree across every node in this
			// file via ExtractFromTree. Eliminates the per-node re-parse that
			// pprof on airflow flagged as 91% of total allocations.
			tree, _ := parser.ParseByName(t.ext.Language(), raw)
			if tree != nil {
				defer tree.Close()
			}
			results := t.ext.ExtractFromTree(ctx, tree, t.ns)
			var localEdges []*model.CodeEdge
			for j, r := range results {
				if j >= len(t.ns) {
					break
				}
				n := t.ns[j]
				localEdges = append(localEdges, r.CallEdges...)
				localEdges = append(localEdges, r.SymbolReferences...)
				if len(r.TypeHints) > 0 && n != nil {
					if n.Properties == nil {
						n.Properties = map[string]any{}
					}
					for k, v := range r.TypeHints {
						n.Properties[k] = v
					}
				}
			}
			out[i] = localEdges
		}(i, t)
	}
	wg.Wait()
	for _, slot := range out {
		*edges = append(*edges, slot...)
	}
}

// buildRegistry maps both ID and (when non-empty) FQN to the originating node.
// Caller passes-by-reference so extractor type-hint writes propagate back.
func buildRegistry(nodes []*model.CodeNode) map[string]*model.CodeNode {
	m := make(map[string]*model.CodeNode, len(nodes)*2)
	for _, n := range nodes {
		if n == nil {
			continue
		}
		if n.ID != "" {
			m[n.ID] = n
		}
		if n.FQN != "" {
			m[n.FQN] = n
		}
	}
	return m
}

// languageAliases collapses related language keys onto a single extractor —
// e.g. JavaScript files fall through to the TypeScript extractor (which
// parses JS as a TS-grammar subset).
var languageAliases = map[string]string{
	"javascript": "typescript",
}

// DetectLanguage maps a file path to an extractor language key, lower-case.
// Returns "" for unsupported extensions; the orchestrator then skips the
// file entirely.
func DetectLanguage(path string) string {
	dot := strings.LastIndex(path, ".")
	if dot < 0 {
		return ""
	}
	switch strings.ToLower(path[dot+1:]) {
	case "java":
		return "java"
	case "ts", "tsx":
		return "typescript"
	case "js", "jsx", "mjs", "cjs":
		return "javascript"
	case "py", "pyw":
		return "python"
	case "go":
		return "go"
	}
	return ""
}

// isLikelyMinified is a cheap heuristic to skip minified JS/CSS/TS bundles:
// files larger than 50 KB whose mean line length exceeds 1000 chars are
// almost certainly minified. Matches the corresponding Java guard.
func isLikelyMinified(path, content string) bool {
	if len(content) < 50_000 {
		return false
	}
	name := path
	if i := strings.LastIndex(path, "/"); i >= 0 {
		name = path[i+1:]
	}
	jsOrCSS := strings.HasSuffix(name, ".js") || strings.HasSuffix(name, ".mjs") ||
		strings.HasSuffix(name, ".cjs") || strings.HasSuffix(name, ".css") ||
		strings.HasSuffix(name, ".jsx") || strings.HasSuffix(name, ".ts")
	if !jsOrCSS && !strings.HasSuffix(name, ".min.js") &&
		!strings.HasSuffix(name, ".bundle.js") {
		return false
	}
	newlines := strings.Count(content, "\n")
	if newlines == 0 {
		newlines = 1
	}
	return len(content)/newlines > 1000
}
