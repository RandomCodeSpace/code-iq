package analyzer

import (
	"os"
	"path/filepath"
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/parser"
)

func makeTree(t *testing.T) string {
	dir := t.TempDir()
	mustWrite := func(p, c string) {
		full := filepath.Join(dir, p)
		_ = os.MkdirAll(filepath.Dir(full), 0755)
		if err := os.WriteFile(full, []byte(c), 0644); err != nil {
			t.Fatal(err)
		}
	}
	mustWrite("a.java", "public class A {}")
	mustWrite("sub/b.py", "x = 1")
	mustWrite("node_modules/skip.js", "skip me")
	mustWrite(".git/HEAD", "ref: refs/heads/main")
	mustWrite(".codeiq/cache/x.sqlite", "blob")
	mustWrite("LICENSE", "MIT")
	return dir
}

func TestDirWalkDiscovery(t *testing.T) {
	dir := makeTree(t)
	disc := NewFileDiscovery()
	files, err := disc.Discover(dir)
	if err != nil {
		t.Fatal(err)
	}
	got := make([]string, 0, len(files))
	for _, f := range files {
		got = append(got, f.RelPath)
	}
	sort.Strings(got)
	want := []string{"a.java", "sub/b.py"}
	if len(got) != len(want) {
		t.Fatalf("Discover() = %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("got[%d] = %q, want %q", i, got[i], want[i])
		}
	}
}

func TestLanguageTagging(t *testing.T) {
	dir := makeTree(t)
	files, err := NewFileDiscovery().Discover(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, f := range files {
		switch f.RelPath {
		case "a.java":
			if f.Language != parser.LanguageJava {
				t.Errorf("a.java lang = %v, want Java", f.Language)
			}
		case "sub/b.py":
			if f.Language != parser.LanguagePython {
				t.Errorf("b.py lang = %v, want Python", f.Language)
			}
		}
	}
}

func TestDeterministicOrder(t *testing.T) {
	dir := makeTree(t)
	disc := NewFileDiscovery()
	a, _ := disc.Discover(dir)
	b, _ := disc.Discover(dir)
	if len(a) != len(b) {
		t.Fatal("non-deterministic count")
	}
	for i := range a {
		if a[i].RelPath != b[i].RelPath {
			t.Fatalf("non-deterministic order at %d: %q != %q", i, a[i].RelPath, b[i].RelPath)
		}
	}
}
