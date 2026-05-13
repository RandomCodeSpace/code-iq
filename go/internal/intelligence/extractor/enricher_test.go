package extractor

import (
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"sync/atomic"
	"testing"
	"time"

	"github.com/randomcodespace/codeiq/go/internal/model"
	"github.com/randomcodespace/codeiq/go/internal/parser"
)

// fakeExtractor is a test-only LanguageExtractor that records each call so we
// can assert the orchestrator's read-once contract and per-language dispatch.
type fakeExtractor struct {
	lang       string
	calls      int32 // counts per-node visits (across both Extract and ExtractFromTree)
	filesSeen  []string
	emitEdge   bool
	emitHint   bool
	edgeKind   model.EdgeKind
	hintKey    string
	hintValue  string
}

func (f *fakeExtractor) Language() string { return f.lang }

// resultFor synthesises a Result for one node — shared between Extract and
// ExtractFromTree so behaviour is identical regardless of call path.
func (f *fakeExtractor) resultFor(node *model.CodeNode) Result {
	r := EmptyResult()
	if f.emitEdge {
		r.CallEdges = []*model.CodeEdge{{
			ID:       "edge:" + f.lang + ":" + node.ID,
			Kind:     f.edgeKind,
			SourceID: node.ID,
			TargetID: node.ID + "-target",
			Properties: map[string]any{
				"confidence":     "PARTIAL",
				"extractor_name": f.lang + "_fake",
			},
		}}
	}
	if f.emitHint {
		r.TypeHints = map[string]string{f.hintKey: f.hintValue}
	}
	return r
}

func (f *fakeExtractor) Extract(ctx Context, node *model.CodeNode) Result {
	atomic.AddInt32(&f.calls, 1)
	f.filesSeen = append(f.filesSeen, ctx.FilePath)
	return f.resultFor(node)
}

func (f *fakeExtractor) ExtractFromTree(ctx Context, _ *parser.Tree, nodes []*model.CodeNode) []Result {
	atomic.AddInt32(&f.calls, int32(len(nodes)))
	f.filesSeen = append(f.filesSeen, ctx.FilePath)
	results := make([]Result, len(nodes))
	for i, n := range nodes {
		if n == nil {
			results[i] = EmptyResult()
			continue
		}
		results[i] = f.resultFor(n)
	}
	return results
}

func TestEnricher_DispatchesPerLanguageAndAppendsEdges(t *testing.T) {
	dir := t.TempDir()
	javaPath := "src/Foo.java"
	pyPath := "src/foo.py"
	writeFile(t, filepath.Join(dir, javaPath), "class Foo {}")
	writeFile(t, filepath.Join(dir, pyPath), "def foo(): pass\n")

	javaExt := &fakeExtractor{
		lang:      "java",
		emitEdge:  true,
		edgeKind:  model.EdgeCalls,
		emitHint:  true,
		hintKey:   "extends_type",
		hintValue: "Bar",
	}
	pyExt := &fakeExtractor{
		lang:     "python",
		emitEdge: true,
		edgeKind: model.EdgeCalls,
	}

	en := NewEnricher(javaExt, pyExt)

	javaNode := model.NewCodeNode("n:java:1", model.NodeClass, "Foo")
	javaNode.FilePath = javaPath
	pyNode := model.NewCodeNode("n:py:1", model.NodeMethod, "foo")
	pyNode.FilePath = pyPath

	nodes := []*model.CodeNode{javaNode, pyNode}
	var edges []*model.CodeEdge

	en.Enrich(nodes, &edges, dir)

	if got, want := len(edges), 2; got != want {
		t.Fatalf("edges = %d, want %d", got, want)
	}
	// Verify per-language dispatch ran each extractor exactly once.
	if atomic.LoadInt32(&javaExt.calls) != 1 {
		t.Fatalf("javaExt.calls = %d, want 1", javaExt.calls)
	}
	if atomic.LoadInt32(&pyExt.calls) != 1 {
		t.Fatalf("pyExt.calls = %d, want 1", pyExt.calls)
	}
	// Type-hint should be stamped onto the node properties.
	if got, ok := javaNode.Properties["extends_type"].(string); !ok || got != "Bar" {
		t.Fatalf("javaNode.Properties[extends_type] = %v, want \"Bar\"", javaNode.Properties["extends_type"])
	}
	// Edge source IDs should match the corresponding node IDs.
	srcs := []string{edges[0].SourceID, edges[1].SourceID}
	sort.Strings(srcs)
	if srcs[0] != "n:java:1" || srcs[1] != "n:py:1" {
		t.Fatalf("edge source IDs = %v, want [n:java:1 n:py:1]", srcs)
	}
}

func TestEnricher_ReadsEachFileOnce(t *testing.T) {
	dir := t.TempDir()
	javaPath := "Same.java"
	writeFile(t, filepath.Join(dir, javaPath), "class Same {}")

	ext := &fakeExtractor{lang: "java"}
	en := NewEnricher(ext)

	// Two nodes share the same file path. The orchestrator must read the file
	// exactly once across both nodes.
	n1 := model.NewCodeNode("n:1", model.NodeClass, "Same")
	n1.FilePath = javaPath
	n2 := model.NewCodeNode("n:2", model.NodeMethod, "doStuff")
	n2.FilePath = javaPath

	var edges []*model.CodeEdge
	en.Enrich([]*model.CodeNode{n1, n2}, &edges, dir)

	// Both nodes saw the same content path; Extract was called twice but
	// fileReadCounter (via filesSeen) records only one distinct file.
	if atomic.LoadInt32(&ext.calls) != 2 {
		t.Fatalf("Extract calls = %d, want 2", ext.calls)
	}
	distinct := map[string]struct{}{}
	for _, p := range ext.filesSeen {
		distinct[p] = struct{}{}
	}
	if got := len(distinct); got != 1 {
		t.Fatalf("distinct files seen = %d, want 1", got)
	}
}

func TestEnricher_SkipsFilteredFiles(t *testing.T) {
	dir := t.TempDir()
	writeFile(t, filepath.Join(dir, "vendor/x.java"), "class X {}")

	ext := &fakeExtractor{lang: "java", emitEdge: true, edgeKind: model.EdgeCalls}
	en := NewEnricher(ext)

	n := model.NewCodeNode("n:1", model.NodeClass, "X")
	n.FilePath = "vendor/x.java"
	n.Properties["file_type"] = "generated"

	var edges []*model.CodeEdge
	en.Enrich([]*model.CodeNode{n}, &edges, dir)

	if got := len(edges); got != 0 {
		t.Fatalf("edges = %d, want 0 (filtered file)", got)
	}
	if atomic.LoadInt32(&ext.calls) != 0 {
		t.Fatalf("Extract calls = %d, want 0", ext.calls)
	}
}

// concurrencyTrackingExtractor records the maximum number of goroutines
// observed inside ExtractFromTree at the same time, so we can assert that
// the orchestrator bounds the fan-out.
type concurrencyTrackingExtractor struct {
	lang     string
	inFlight atomic.Int32
	maxSeen  atomic.Int32
	hold     time.Duration
}

func (c *concurrencyTrackingExtractor) Language() string { return c.lang }

func (c *concurrencyTrackingExtractor) Extract(ctx Context, node *model.CodeNode) Result {
	// Unused for this test; orchestrator hits ExtractFromTree.
	return EmptyResult()
}

func (c *concurrencyTrackingExtractor) ExtractFromTree(_ Context, _ *parser.Tree, nodes []*model.CodeNode) []Result {
	cur := c.inFlight.Add(1)
	defer c.inFlight.Add(-1)
	for {
		old := c.maxSeen.Load()
		if cur <= old || c.maxSeen.CompareAndSwap(old, cur) {
			break
		}
	}
	time.Sleep(c.hold)
	results := make([]Result, len(nodes))
	for i := range results {
		results[i] = EmptyResult()
	}
	return results
}

func TestEnricher_BoundedConcurrency(t *testing.T) {
	// Generate enough files to overwhelm the goroutine pool if it were
	// unbounded — 4 * cap files at minimum.
	cap := 2 * runtime.GOMAXPROCS(0)
	nFiles := 4 * cap
	dir := t.TempDir()
	nodes := make([]*model.CodeNode, 0, nFiles)
	for i := 0; i < nFiles; i++ {
		// Deterministic distinct file paths so the orchestrator schedules
		// one task per file.
		rel := filepath.Join("src", "f", "F"+itoa(i)+".java")
		writeFile(t, filepath.Join(dir, rel), "class F"+itoa(i)+" {}")
		n := model.NewCodeNode("n:"+itoa(i), model.NodeClass, "F"+itoa(i))
		n.FilePath = rel
		nodes = append(nodes, n)
	}
	ext := &concurrencyTrackingExtractor{lang: "java", hold: 25 * time.Millisecond}
	en := NewEnricher(ext)
	var edges []*model.CodeEdge
	en.Enrich(nodes, &edges, dir)
	peak := ext.maxSeen.Load()
	if peak == 0 {
		t.Fatal("peak in-flight was 0 — orchestrator never invoked the extractor")
	}
	if int(peak) > cap {
		t.Fatalf("peak concurrent ExtractFromTree calls = %d, want <= %d (2*GOMAXPROCS)", peak, cap)
	}
}

func itoa(i int) string {
	const digits = "0123456789"
	if i == 0 {
		return "0"
	}
	out := make([]byte, 0, 8)
	for i > 0 {
		out = append([]byte{digits[i%10]}, out...)
		i /= 10
	}
	return string(out)
}

func TestEnricher_NoExtractorsIsNoop(t *testing.T) {
	en := NewEnricher()
	n := model.NewCodeNode("n:1", model.NodeClass, "Foo")
	n.FilePath = "Foo.java"
	var edges []*model.CodeEdge
	en.Enrich([]*model.CodeNode{n}, &edges, t.TempDir())
	if len(edges) != 0 {
		t.Fatalf("edges = %d, want 0", len(edges))
	}
}

func TestDetectLanguage(t *testing.T) {
	cases := map[string]string{
		"foo.java":     "java",
		"foo.ts":       "typescript",
		"foo.tsx":      "typescript",
		"foo.js":       "javascript",
		"foo.py":       "python",
		"foo.go":       "go",
		"foo.unknown":  "",
		"NO_EXTENSION": "",
	}
	for path, want := range cases {
		if got := DetectLanguage(path); got != want {
			t.Errorf("DetectLanguage(%q) = %q, want %q", path, got, want)
		}
	}
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

// Note: parser package's tree-sitter wrappers aren't needed by the orchestrator
// test — fake extractors don't call parser.Parse. Real extractors (Tasks 19–22)
// drive the real parser themselves.
var _ = (*fakeExtractor)(nil) // silence unused-warning during early TDD steps
