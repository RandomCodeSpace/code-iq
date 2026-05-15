package analyzer

import (
	"os"
	"path/filepath"
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/cache"
	"github.com/randomcodespace/codeiq/internal/detector"

	// Register a couple of phase-1 detectors so the Analyzer Registry
	// doesn't sit empty (Diff doesn't actually run detectors but the
	// Analyzer requires a non-nil Registry, satisfied here via blank imports).
	_ "github.com/randomcodespace/codeiq/internal/detector/jvm/java"
	_ "github.com/randomcodespace/codeiq/internal/detector/python"
)

func mustOpenCache(t *testing.T) (*cache.Cache, string) {
	t.Helper()
	dir := t.TempDir()
	c, err := cache.Open(filepath.Join(dir, "c.sqlite"))
	if err != nil {
		t.Fatalf("cache: %v", err)
	}
	return c, dir
}

func writeDiffFile(t *testing.T, root, rel, content string) {
	t.Helper()
	full := filepath.Join(root, rel)
	if err := os.MkdirAll(filepath.Dir(full), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestDiffEmptyCacheAndEmptyRoot(t *testing.T) {
	c, root := mustOpenCache(t)
	defer c.Close()
	a := NewAnalyzer(Options{Cache: c, Registry: detector.Default})
	d, err := a.Diff(root)
	if err != nil {
		t.Fatalf("diff: %v", err)
	}
	if len(d.Added)+len(d.Modified)+len(d.Deleted)+len(d.Unchanged) != 0 {
		t.Fatalf("empty root + empty cache: got %+v", d)
	}
}

func TestDiffDetectsAddedFile(t *testing.T) {
	c, root := mustOpenCache(t)
	defer c.Close()
	writeDiffFile(t, root, "X.java", "public class X {}")
	a := NewAnalyzer(Options{Cache: c, Registry: detector.Default})
	d, err := a.Diff(root)
	if err != nil {
		t.Fatal(err)
	}
	if len(d.Added) != 1 || d.Added[0] != "X.java" {
		t.Fatalf("Added = %v, want [X.java]", d.Added)
	}
	if len(d.Modified)+len(d.Deleted)+len(d.Unchanged) != 0 {
		t.Fatalf("other buckets non-empty: %+v", d)
	}
}

func TestDiffDetectsUnchangedFile(t *testing.T) {
	c, root := mustOpenCache(t)
	defer c.Close()
	src := "public class X {}"
	writeDiffFile(t, root, "X.java", src)
	_ = c.Put(&cache.Entry{ContentHash: cache.HashString(src), Path: "X.java", Language: "java", ParsedAt: "t"})
	a := NewAnalyzer(Options{Cache: c, Registry: detector.Default})
	d, err := a.Diff(root)
	if err != nil {
		t.Fatal(err)
	}
	if len(d.Unchanged) != 1 || d.Unchanged[0] != "X.java" {
		t.Fatalf("Unchanged = %v, want [X.java]", d.Unchanged)
	}
	if len(d.Added)+len(d.Modified)+len(d.Deleted) != 0 {
		t.Fatalf("other buckets non-empty: %+v", d)
	}
}

func TestDiffDetectsModifiedFile(t *testing.T) {
	c, root := mustOpenCache(t)
	defer c.Close()
	writeDiffFile(t, root, "X.java", "public class X { int v = 2; }")
	_ = c.Put(&cache.Entry{
		ContentHash: cache.HashString("public class X {}"),
		Path:        "X.java",
		Language:    "java",
		ParsedAt:    "t",
	})
	a := NewAnalyzer(Options{Cache: c, Registry: detector.Default})
	d, err := a.Diff(root)
	if err != nil {
		t.Fatal(err)
	}
	if len(d.Modified) != 1 || d.Modified[0] != "X.java" {
		t.Fatalf("Modified = %v, want [X.java]", d.Modified)
	}
}

func TestDiffDetectsDeletedFile(t *testing.T) {
	c, root := mustOpenCache(t)
	defer c.Close()
	_ = c.Put(&cache.Entry{ContentHash: "h", Path: "Y.java", Language: "java", ParsedAt: "t"})
	a := NewAnalyzer(Options{Cache: c, Registry: detector.Default})
	d, err := a.Diff(root)
	if err != nil {
		t.Fatal(err)
	}
	if len(d.Deleted) != 1 || d.Deleted[0] != "Y.java" {
		t.Fatalf("Deleted = %v, want [Y.java]", d.Deleted)
	}
}

func TestDiffMixedScenario(t *testing.T) {
	c, root := mustOpenCache(t)
	defer c.Close()
	writeDiffFile(t, root, "A.java", "class A {}")
	writeDiffFile(t, root, "B.java", "class B {}")
	writeDiffFile(t, root, "C.java", "class C v2 {}")
	_ = c.Put(&cache.Entry{ContentHash: cache.HashString("class A {}"), Path: "A.java", Language: "java", ParsedAt: "t"})
	_ = c.Put(&cache.Entry{ContentHash: cache.HashString("class C {}"), Path: "C.java", Language: "java", ParsedAt: "t"})
	_ = c.Put(&cache.Entry{ContentHash: "h-d", Path: "D.java", Language: "java", ParsedAt: "t"})
	a := NewAnalyzer(Options{Cache: c, Registry: detector.Default})
	d, err := a.Diff(root)
	if err != nil {
		t.Fatal(err)
	}
	check := func(label string, got, want []string) {
		t.Helper()
		sort.Strings(got)
		sort.Strings(want)
		if len(got) != len(want) {
			t.Errorf("%s: got %v, want %v", label, got, want)
			return
		}
		for i := range got {
			if got[i] != want[i] {
				t.Errorf("%s[%d]: got %q, want %q", label, i, got[i], want[i])
			}
		}
	}
	check("Added", d.Added, []string{"B.java"})
	check("Modified", d.Modified, []string{"C.java"})
	check("Deleted", d.Deleted, []string{"D.java"})
	check("Unchanged", d.Unchanged, []string{"A.java"})
}
