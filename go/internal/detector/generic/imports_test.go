package generic

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestImportsJava(t *testing.T) {
	d := NewGenericImportsDetector()
	ctx := &detector.Context{
		FilePath: "src/Foo.java",
		Language: "java",
		Content: `package com.x;
import java.util.List;
import java.util.Map;
import com.example.Bar;
public class Foo {}`,
	}
	r := d.Detect(ctx)
	if len(r.Edges) != 3 {
		t.Fatalf("expected 3 IMPORTS edges, got %d: %+v", len(r.Edges), r.Edges)
	}
	for _, e := range r.Edges {
		if e.Kind != model.EdgeImports {
			t.Errorf("kind = %v, want IMPORTS", e.Kind)
		}
	}
	// Source node (the file's MODULE) plus the 3 target external modules.
	if len(r.Nodes) != 4 {
		t.Fatalf("expected 4 nodes (file + 3 imports), got %d", len(r.Nodes))
	}
}

func TestImportsPython(t *testing.T) {
	d := NewGenericImportsDetector()
	ctx := &detector.Context{
		FilePath: "app/x.py",
		Language: "python",
		Content: `import os
import sys
from django.db import models
from typing import List as L
`,
	}
	r := d.Detect(ctx)
	if len(r.Edges) != 4 {
		t.Fatalf("expected 4 IMPORTS edges, got %d: %+v", len(r.Edges), r.Edges)
	}
	targets := make([]string, 0, len(r.Edges))
	for _, e := range r.Edges {
		if mod, ok := e.Properties["module"]; ok {
			targets = append(targets, mod.(string))
		}
	}
	sort.Strings(targets)
	wantTargets := []string{"django.db", "os", "sys", "typing"}
	for i, w := range wantTargets {
		if i >= len(targets) || targets[i] != w {
			t.Errorf("targets[%d] = %q, want %q (full: %v)", i, targets[i], w, targets)
		}
	}
}

func TestImportsNegativeUnsupportedLanguage(t *testing.T) {
	d := NewGenericImportsDetector()
	ctx := &detector.Context{FilePath: "x.txt", Language: "text", Content: "hello"}
	if r := d.Detect(ctx); len(r.Nodes) != 0 || len(r.Edges) != 0 {
		t.Fatalf("expected empty result, got %+v", r)
	}
}

func TestImportsDeterminism(t *testing.T) {
	d := NewGenericImportsDetector()
	ctx := &detector.Context{
		FilePath: "app/x.py",
		Language: "python",
		Content:  "import a\nimport b\nimport c\n",
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic edge count")
	}
	for i := range r1.Edges {
		if r1.Edges[i].TargetID != r2.Edges[i].TargetID {
			t.Fatalf("non-deterministic target at %d", i)
		}
	}
}
