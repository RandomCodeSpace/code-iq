package analyzer

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/randomcodespace/codeiq/internal/cache"
	"github.com/randomcodespace/codeiq/internal/detector"

	// Register the 5 phase-1 detectors via blank imports.
	_ "github.com/randomcodespace/codeiq/internal/detector/generic"
	_ "github.com/randomcodespace/codeiq/internal/detector/jvm/java"
	_ "github.com/randomcodespace/codeiq/internal/detector/python"
)

const fixtureJava = `package com.x;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {
    @GetMapping("/{id}")
    public String get(Long id) { return ""; }
}
`

const fixturePython = `from django.db import models

class Author(models.Model):
    name = models.CharField(max_length=100)
`

func TestAnalyzerEndToEnd(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "UserController.java"), []byte(fixtureJava), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "models.py"), []byte(fixturePython), 0644); err != nil {
		t.Fatal(err)
	}

	c, err := cache.Open(filepath.Join(dir, "cache.sqlite"))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()

	a := NewAnalyzer(Options{Cache: c, Registry: detector.Default})
	stats, err := a.Run(dir)
	if err != nil {
		t.Fatal(err)
	}
	if stats.Files != 2 {
		t.Fatalf("Files = %d, want 2", stats.Files)
	}
	if stats.Nodes < 2 {
		t.Fatalf("Nodes = %d, want >= 2", stats.Nodes)
	}
	// Verify both files round-tripped through the cache.
	count := 0
	_ = c.IterateAll(func(*cache.Entry) error { count++; return nil })
	if count != 2 {
		t.Fatalf("cache entries = %d, want 2", count)
	}
}

func TestStatsHasIncrementalCounters(t *testing.T) {
	var s Stats
	// Compile-time check that the new fields exist with the expected names.
	_ = s.Added
	_ = s.Modified
	_ = s.Deleted
	_ = s.Unchanged
	_ = s.CacheHits
}

func TestProcessFileSkipsOnCacheHit(t *testing.T) {
	root := t.TempDir()
	cachePath := filepath.Join(root, ".codeiq", "cache.sqlite")
	if err := os.MkdirAll(filepath.Dir(cachePath), 0o755); err != nil {
		t.Fatal(err)
	}
	src := "public class A {}"
	if err := os.WriteFile(filepath.Join(root, "A.java"), []byte(src), 0o644); err != nil {
		t.Fatal(err)
	}

	c, err := cache.Open(cachePath)
	if err != nil {
		t.Fatalf("cache: %v", err)
	}
	defer c.Close()

	// Seed the cache with a row for this content hash. processFile MUST
	// not re-parse the file when its hash already lives in the cache.
	if err := c.Put(&cache.Entry{
		ContentHash: cache.HashString(src),
		Path:        "A.java",
		Language:    "java",
		ParsedAt:    "2026-01-01T00:00:00Z",
	}); err != nil {
		t.Fatal(err)
	}

	a := NewAnalyzer(Options{Cache: c, Registry: detector.Default, Workers: 1})
	stats, err := a.Run(root)
	if err != nil {
		t.Fatalf("run: %v", err)
	}
	if stats.CacheHits != 1 {
		t.Fatalf("CacheHits = %d, want 1", stats.CacheHits)
	}
	if stats.Files != 1 {
		t.Fatalf("Files = %d, want 1", stats.Files)
	}
	if stats.Unchanged != 1 {
		t.Fatalf("Unchanged = %d, want 1", stats.Unchanged)
	}
}

func TestForceBypassesCacheHit(t *testing.T) {
	root := t.TempDir()
	cachePath := filepath.Join(root, ".codeiq", "cache.sqlite")
	if err := os.MkdirAll(filepath.Dir(cachePath), 0o755); err != nil {
		t.Fatal(err)
	}
	src := "public class A {}"
	if err := os.WriteFile(filepath.Join(root, "A.java"), []byte(src), 0o644); err != nil {
		t.Fatal(err)
	}

	c, err := cache.Open(cachePath)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	_ = c.Put(&cache.Entry{
		ContentHash: cache.HashString(src),
		Path:        "A.java",
		Language:    "java",
		ParsedAt:    "t",
	})

	a := NewAnalyzer(Options{Cache: c, Registry: detector.Default, Workers: 1, Force: true})
	stats, err := a.Run(root)
	if err != nil {
		t.Fatal(err)
	}
	if stats.CacheHits != 0 {
		t.Fatalf("Force=true should bypass cache; CacheHits = %d", stats.CacheHits)
	}
}

func TestRunPurgesDeletedFiles(t *testing.T) {
	root := t.TempDir()
	cachePath := filepath.Join(root, ".codeiq", "cache.sqlite")
	if err := os.MkdirAll(filepath.Dir(cachePath), 0o755); err != nil {
		t.Fatal(err)
	}
	c, err := cache.Open(cachePath)
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()

	// Seed a phantom file that's gone from disk.
	if err := c.Put(&cache.Entry{
		ContentHash: "ghost-hash",
		Path:        "deleted.java",
		Language:    "java",
		ParsedAt:    "t",
	}); err != nil {
		t.Fatal(err)
	}
	if !c.Has("ghost-hash") {
		t.Fatal("seed didn't take")
	}
	if err := os.WriteFile(filepath.Join(root, "real.java"), []byte("class R {}"), 0o644); err != nil {
		t.Fatal(err)
	}

	a := NewAnalyzer(Options{Cache: c, Registry: detector.Default, Workers: 1})
	stats, err := a.Run(root)
	if err != nil {
		t.Fatal(err)
	}
	if c.Has("ghost-hash") {
		t.Fatal("deleted file's cache row not purged")
	}
	if stats.Deleted != 1 {
		t.Fatalf("Deleted = %d, want 1", stats.Deleted)
	}
}

func TestAnalyzerDeterminism(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "UserController.java"), []byte(fixtureJava), 0644); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "models.py"), []byte(fixturePython), 0644); err != nil {
		t.Fatal(err)
	}
	c1, _ := cache.Open(filepath.Join(dir, "c1.sqlite"))
	c2, _ := cache.Open(filepath.Join(dir, "c2.sqlite"))
	defer c1.Close()
	defer c2.Close()
	a1 := NewAnalyzer(Options{Cache: c1, Registry: detector.Default})
	a2 := NewAnalyzer(Options{Cache: c2, Registry: detector.Default})
	s1, _ := a1.Run(dir)
	s2, _ := a2.Run(dir)
	if s1.Nodes != s2.Nodes || s1.Edges != s2.Edges || s1.Files != s2.Files {
		t.Fatalf("non-deterministic stats: %+v vs %+v", s1, s2)
	}
}
