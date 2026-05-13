package extractor

import (
	"os"
	"path/filepath"
	"sort"
	"sync/atomic"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// fakeExtractor is a test-only LanguageExtractor that records each call so we
// can assert the orchestrator's read-once contract and per-language dispatch.
type fakeExtractor struct {
	lang       string
	calls      int32 // atomic counter of Extract() invocations
	filesSeen  []string
	emitEdge   bool
	emitHint   bool
	edgeKind   model.EdgeKind
	hintKey    string
	hintValue  string
}

func (f *fakeExtractor) Language() string { return f.lang }

func (f *fakeExtractor) Extract(ctx Context, node *model.CodeNode) Result {
	atomic.AddInt32(&f.calls, 1)
	f.filesSeen = append(f.filesSeen, ctx.FilePath)
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
