package python

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const pyStructSource = `import os
import json
from typing import List, Optional

__all__ = ['Service', 'helper']

def helper(x):
    return x

async def fetch_all():
    return []

class Service:
    def __init__(self, name):
        self.name = name

    @staticmethod
    def factory():
        return Service("default")

class Subclass(Service):
    pass
`

func TestPythonStructuresPositive(t *testing.T) {
	d := NewPythonStructuresDetector()
	ctx := &detector.Context{
		FilePath: "app/svc.py",
		Language: "python",
		Content:  pyStructSource,
	}
	r := d.Detect(ctx)
	var classes, methods, modules int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeClass:
			classes++
		case model.NodeMethod:
			methods++
		case model.NodeModule:
			modules++
		}
	}
	if classes != 2 {
		t.Errorf("expected 2 classes, got %d", classes)
	}
	if methods < 4 {
		t.Errorf("expected at least 4 methods (top-level + class methods), got %d", methods)
	}
	// 1 __all__ module + 1 file-as-module (anchor for imports edges).
	if modules != 2 {
		t.Errorf("expected 2 module nodes (__all__ + file anchor), got %d", modules)
	}
	// Import edges + defines + extends
	if len(r.Edges) < 4 {
		t.Errorf("expected at least 4 edges, got %d", len(r.Edges))
	}
}

func TestPythonStructuresNegative(t *testing.T) {
	d := NewPythonStructuresDetector()
	r := d.Detect(&detector.Context{FilePath: "x.py", Language: "python", Content: "x = 1\ny = 2\n"})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestPythonStructuresDeterminism(t *testing.T) {
	d := NewPythonStructuresDetector()
	ctx := &detector.Context{FilePath: "app/svc.py", Language: "python", Content: pyStructSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
	sort.Slice(r1.Nodes, func(i, j int) bool { return r1.Nodes[i].ID < r1.Nodes[j].ID })
	sort.Slice(r2.Nodes, func(i, j int) bool { return r2.Nodes[i].ID < r2.Nodes[j].ID })
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic at %d", i)
		}
	}
}
