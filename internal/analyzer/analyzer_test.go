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
