package kotlin

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const kotlinStructuresSample = `package com.example

import com.example.other.Base
import com.example.other.Helper

class User : Base(), Helper {
    fun greet(): String = "hi"
}

interface Greeter {
    fun greet(): String
}

object Singleton {
    val name = "x"
}

data class Point(val x: Int, val y: Int)
`

func TestKotlinStructuresPositive(t *testing.T) {
	d := NewKotlinStructuresDetector()
	ctx := &detector.Context{FilePath: "src/A.kt", Language: "kotlin", Content: kotlinStructuresSample}
	r := d.Detect(ctx)
	if r == nil {
		t.Fatal("Detect returned nil")
	}
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes, got none")
	}
	if len(r.Edges) == 0 {
		t.Fatal("expected import + extends edges, got none")
	}

	var hasClass, hasInterface, hasObject, hasFun bool
	for _, n := range r.Nodes {
		switch {
		case n.Label == "User" && n.Kind == model.NodeClass:
			hasClass = true
		case n.Label == "Greeter" && n.Kind == model.NodeInterface:
			hasInterface = true
		case n.Label == "Singleton" && n.Kind == model.NodeClass && n.Properties["type"] == "object":
			hasObject = true
		case n.Label == "greet" && n.Kind == model.NodeMethod:
			hasFun = true
		}
	}
	if !hasClass {
		t.Error("missing User class node")
	}
	if !hasInterface {
		t.Error("missing Greeter interface node")
	}
	if !hasObject {
		t.Error("missing Singleton object node")
	}
	if !hasFun {
		t.Error("missing greet method node")
	}

	// Check imports edge exists
	var hasImport bool
	for _, e := range r.Edges {
		if e.Kind == model.EdgeImports && e.TargetID == "com.example.other.Base" {
			hasImport = true
		}
	}
	if !hasImport {
		t.Error("missing import edge for com.example.other.Base")
	}
}

func TestKotlinStructuresNegative(t *testing.T) {
	d := NewKotlinStructuresDetector()
	ctx := &detector.Context{FilePath: "src/A.kt", Language: "kotlin", Content: ""}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 || len(r.Edges) != 0 {
		t.Fatalf("expected empty result on empty input, got %d nodes / %d edges", len(r.Nodes), len(r.Edges))
	}
}

func TestKotlinStructuresDeterminism(t *testing.T) {
	d := NewKotlinStructuresDetector()
	ctx := &detector.Context{FilePath: "src/A.kt", Language: "kotlin", Content: kotlinStructuresSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatalf("nondeterministic counts: r1 %d/%d r2 %d/%d",
			len(r1.Nodes), len(r1.Edges), len(r2.Nodes), len(r2.Edges))
	}
	sort.Slice(r1.Nodes, func(i, j int) bool { return r1.Nodes[i].ID < r1.Nodes[j].ID })
	sort.Slice(r2.Nodes, func(i, j int) bool { return r2.Nodes[i].ID < r2.Nodes[j].ID })
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("nondeterministic at %d: %q vs %q", i, r1.Nodes[i].ID, r2.Nodes[i].ID)
		}
	}
}
