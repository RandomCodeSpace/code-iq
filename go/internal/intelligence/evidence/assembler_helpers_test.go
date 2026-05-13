package evidence

import (
	"strings"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/intelligence/lexical"
	iqquery "github.com/randomcodespace/codeiq/go/internal/intelligence/query"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestBoundSnippetWithinLimit(t *testing.T) {
	cs := lexical.CodeSnippet{
		Source:    "a\nb\nc\n",
		FilePath:  "x.java",
		LineStart: 1,
		LineEnd:   3,
		Language:  "java",
	}
	got := boundSnippet(cs, 50)
	if got.LineStart != cs.LineStart || got.LineEnd != cs.LineEnd {
		t.Fatalf("under-limit snippet range mutated: got %d-%d, want %d-%d",
			got.LineStart, got.LineEnd, cs.LineStart, cs.LineEnd)
	}
	if got.Source != cs.Source {
		t.Errorf("source mutated: got %q, want %q", got.Source, cs.Source)
	}
}

func TestBoundSnippetTruncates(t *testing.T) {
	lines := make([]string, 0, 100)
	for i := 1; i <= 100; i++ {
		lines = append(lines, "L")
	}
	cs := lexical.CodeSnippet{
		Source:    strings.Join(lines, "\n"),
		FilePath:  "big.go",
		LineStart: 10,
		LineEnd:   109, // 100 lines
		Language:  "go",
	}
	got := boundSnippet(cs, 20)
	span := got.LineEnd - got.LineStart + 1
	if span != 20 {
		t.Fatalf("span = %d, want 20", span)
	}
	if got.LineStart != cs.LineStart {
		t.Errorf("start drifted: got %d, want %d", got.LineStart, cs.LineStart)
	}
	if strings.Count(got.Source, "\n") < 20 {
		t.Errorf("source should contain at least 20 lines, got %d", strings.Count(got.Source, "\n"))
	}
}

func TestInferLanguage(t *testing.T) {
	cases := map[string]string{
		"X.java":     "java",
		"foo.ts":     "typescript",
		"foo.tsx":    "typescript",
		"foo.js":     "javascript",
		"foo.jsx":    "javascript",
		"a.py":       "python",
		"main.go":    "go",
		"src.rs":     "rust",
		"X.cs":       "csharp",
		"noext":      "unknown",
		"weird.xyz":  "unknown",
		"UPPER.JAVA": "java",
		"":           "unknown",
	}
	for path, want := range cases {
		if got := inferLanguage(path); got != want {
			t.Errorf("inferLanguage(%q) = %q, want %q", path, got, want)
		}
	}
}

func TestUniqueSortedFiles(t *testing.T) {
	nodes := []*model.CodeNode{
		{FilePath: "b/Y.java"},
		{FilePath: "a/X.java"},
		{FilePath: "b/Y.java"}, // dup
		{FilePath: ""},          // skipped
		{FilePath: "a/X.java"}, // dup
	}
	got := uniqueSortedFiles(nodes)
	want := []string{"a/X.java", "b/Y.java"}
	if len(got) != len(want) {
		t.Fatalf("len = %d, want %d (got %v)", len(got), len(want), got)
	}
	for i, w := range want {
		if got[i] != w {
			t.Errorf("[%d] = %q, want %q", i, got[i], w)
		}
	}
}

func TestProvenanceForBundlesFields(t *testing.T) {
	n := &model.CodeNode{
		ID:        "x",
		Kind:      model.NodeClass,
		FilePath:  "src/X.java",
		LineStart: 10,
		LineEnd:   20,
		Properties: map[string]any{
			"prov_repo":   "github.com/foo",
			"prov_commit": "abc123",
			"random":      "ignored",
		},
	}
	got := provenanceFor(n)
	if got["file_path"] != "src/X.java" {
		t.Errorf("file_path = %v", got["file_path"])
	}
	if got["line_start"] != 10 {
		t.Errorf("line_start = %v", got["line_start"])
	}
	if got["line_end"] != 20 {
		t.Errorf("line_end = %v", got["line_end"])
	}
	if got["kind"] == nil {
		t.Errorf("kind should be present")
	}
	if got["prov_repo"] != "github.com/foo" {
		t.Errorf("prov_repo = %v", got["prov_repo"])
	}
	if got["prov_commit"] != "abc123" {
		t.Errorf("prov_commit = %v", got["prov_commit"])
	}
	if _, leaked := got["random"]; leaked {
		t.Errorf("non-prov_ property leaked: %v", got)
	}
}

func TestDeriveCapability(t *testing.T) {
	cases := map[iqquery.QueryRoute]Capability{
		iqquery.QueryRouteGraphFirst:   CapExact,
		iqquery.QueryRouteMerged:       CapPartial,
		iqquery.QueryRouteLexicalFirst: CapLexicalOnly,
		iqquery.QueryRouteDegraded:     CapUnsupported,
	}
	for route, want := range cases {
		if got := deriveCapability(route); got != want {
			t.Errorf("deriveCapability(%s) = %s, want %s", route, got, want)
		}
	}
}

func TestResolveMaxLinesClamping(t *testing.T) {
	// nil request → return configured.
	if got := resolveMaxLines(nil, 30); got != 30 {
		t.Errorf("nil requested → %d, want 30", got)
	}
	// requested < 1 → coerced to 1, then clamped at configured.
	zero := 0
	if got := resolveMaxLines(&zero, 30); got != 1 {
		t.Errorf("zero requested → %d, want 1", got)
	}
	neg := -5
	if got := resolveMaxLines(&neg, 30); got != 1 {
		t.Errorf("negative requested → %d, want 1", got)
	}
	// requested > configured → capped at configured.
	big := 9999
	if got := resolveMaxLines(&big, 30); got != 30 {
		t.Errorf("big requested → %d, want 30", got)
	}
	// requested between 1 and configured → return as-is.
	mid := 10
	if got := resolveMaxLines(&mid, 30); got != 10 {
		t.Errorf("mid requested → %d, want 10", got)
	}
}
