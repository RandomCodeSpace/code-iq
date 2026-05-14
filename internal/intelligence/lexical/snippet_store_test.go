package lexical

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"testing"

	"github.com/randomcodespace/codeiq/internal/model"
)

// writeFile is a tiny helper for test fixtures.
func writeFile(t *testing.T, dir, name, content string) string {
	t.Helper()
	full := filepath.Join(dir, name)
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
	return full
}

func TestSnippetStoreExtractDefaultContext(t *testing.T) {
	dir := t.TempDir()
	src := strings.Join([]string{
		"line 1",
		"line 2",
		"line 3",
		"line 4",
		"line 5",
		"line 6",
		"line 7",
		"line 8",
		"line 9",
		"line 10",
	}, "\n")
	writeFile(t, dir, "src/A.java", src)
	node := model.NewCodeNode("a:A", model.NodeClass, "A")
	node.FilePath = "src/A.java"
	node.LineStart = 5
	node.LineEnd = 6

	store := NewSnippetStore()
	cs, ok := store.Extract(node, dir)
	if !ok {
		t.Fatal("Extract returned ok=false")
	}
	// Default context = 3 → start=max(1,5-3)=2, end=min(10,6+3)=9
	if cs.LineStart != 2 || cs.LineEnd != 9 {
		t.Fatalf("range = %d-%d, want 2-9", cs.LineStart, cs.LineEnd)
	}
	if !strings.HasPrefix(cs.Source, "line 2\n") {
		t.Fatalf("source must start at line 2, got: %q", cs.Source)
	}
	if !strings.Contains(cs.Source, "line 9\n") {
		t.Fatalf("source must contain line 9, got: %q", cs.Source)
	}
	if strings.Contains(cs.Source, "line 1") {
		t.Fatalf("source must NOT contain line 1, got: %q", cs.Source)
	}
	if cs.FilePath != "src/A.java" {
		t.Fatalf("filePath = %q, want src/A.java", cs.FilePath)
	}
	if cs.Language != "java" {
		t.Fatalf("language = %q, want java", cs.Language)
	}
}

func TestSnippetStoreCapsAtMaxLines(t *testing.T) {
	dir := t.TempDir()
	var lines []string
	for i := 1; i <= 200; i++ {
		lines = append(lines, "l"+strconv.Itoa(i))
	}
	writeFile(t, dir, "big.go", strings.Join(lines, "\n"))
	node := model.NewCodeNode("b", model.NodeMethod, "f")
	node.FilePath = "big.go"
	node.LineStart = 50
	node.LineEnd = 150 // span 101 lines, would explode without cap

	store := NewSnippetStore()
	cs, ok := store.Extract(node, dir)
	if !ok {
		t.Fatal("ok=false")
	}
	span := cs.LineEnd - cs.LineStart + 1
	if span != MaxSnippetLines {
		t.Fatalf("span = %d, want exactly %d", span, MaxSnippetLines)
	}
	// Centre = (50+150)/2 = 100; start = 100-25 = 75; end = 75+49 = 124
	if cs.LineStart != 75 || cs.LineEnd != 124 {
		t.Fatalf("range = %d-%d, want 75-124", cs.LineStart, cs.LineEnd)
	}
}

func TestSnippetStorePathTraversalGuard(t *testing.T) {
	dir := t.TempDir()
	// Create a file outside root and a normal file inside.
	outside := t.TempDir()
	writeFile(t, outside, "secret.txt", "do not read me\n")
	writeFile(t, dir, "ok.txt", "ok\n")

	node := model.NewCodeNode("x", model.NodeClass, "X")
	node.FilePath = filepath.Join("..", filepath.Base(outside), "secret.txt")
	node.LineStart = 1

	store := NewSnippetStore()
	if _, ok := store.Extract(node, dir); ok {
		t.Fatal("path traversal must be refused")
	}
}

func TestSnippetStoreMissingFileReturnsFalse(t *testing.T) {
	dir := t.TempDir()
	node := model.NewCodeNode("y", model.NodeClass, "Y")
	node.FilePath = "no/such/file.java"
	node.LineStart = 1
	store := NewSnippetStore()
	if _, ok := store.Extract(node, dir); ok {
		t.Fatal("missing file must return ok=false")
	}
}

func TestSnippetStoreNoLocationReturnsFalse(t *testing.T) {
	dir := t.TempDir()
	store := NewSnippetStore()
	// missing FilePath
	a := model.NewCodeNode("a", model.NodeClass, "A")
	if _, ok := store.Extract(a, dir); ok {
		t.Fatal("no filePath must return ok=false")
	}
	// zero LineStart
	b := model.NewCodeNode("b", model.NodeClass, "B")
	b.FilePath = "foo.java"
	if _, ok := store.Extract(b, dir); ok {
		t.Fatal("zero lineStart must return ok=false")
	}
}

func TestInferLanguage(t *testing.T) {
	cases := map[string]string{
		"X.java":    "java",
		"foo.ts":    "typescript",
		"foo.tsx":   "typescript",
		"foo.js":    "javascript",
		"foo.jsx":   "javascript",
		"a.py":      "python",
		"main.go":   "go",
		"src.rs":    "rust",
		"X.cs":      "csharp",
		"a.cpp":     "cpp",
		"a.cc":      "cpp",
		"a.cxx":     "cpp",
		"a.h":       "cpp",
		"a.hpp":     "cpp",
		"K.kt":      "kotlin",
		"S.scala":   "scala",
		"S.sc":      "scala",
		"noext":     "unknown",
		"weird.xyz": "unknown",
		"UPPER.JAVA": "java", // tolerant of case
	}
	for path, want := range cases {
		got := InferLanguage(path)
		if got != want {
			t.Errorf("InferLanguage(%q) = %q, want %q", path, got, want)
		}
	}
}

