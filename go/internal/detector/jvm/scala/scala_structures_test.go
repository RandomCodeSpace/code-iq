package scala

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const scalaStructuresSample = `package com.example

import com.example.other.Base
import com.example.other.Mixin

trait Greeter {
  def greet(): String
}

object Singleton {
  val name = "x"
}

class Repo extends Base {
  def find(id: Long) = null
}
`

const scalaExtendsWith = `class Service extends Actor with Serializable with Logging
`

func TestScalaStructuresPositive(t *testing.T) {
	d := NewScalaStructuresDetector()
	ctx := &detector.Context{FilePath: "src/A.scala", Language: "scala", Content: scalaStructuresSample}
	r := d.Detect(ctx)
	if r == nil {
		t.Fatal("Detect returned nil")
	}
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes, got none")
	}

	var hasClass, hasTrait, hasObject, hasDef bool
	for _, n := range r.Nodes {
		switch {
		case n.Label == "Repo" && n.Kind == model.NodeClass:
			hasClass = true
		case n.Label == "Greeter" && n.Kind == model.NodeInterface && n.Properties["type"] == "trait":
			hasTrait = true
		case n.Label == "Singleton" && n.Kind == model.NodeClass && n.Properties["type"] == "object":
			hasObject = true
		case n.Label == "find" && n.Kind == model.NodeMethod:
			hasDef = true
		}
	}
	if !hasClass {
		t.Error("missing Repo class node")
	}
	if !hasTrait {
		t.Error("missing Greeter trait node")
	}
	if !hasObject {
		t.Error("missing Singleton object node")
	}
	if !hasDef {
		t.Error("missing find method node")
	}

	// Imports
	var hasImport bool
	for _, e := range r.Edges {
		if e.Kind == model.EdgeImports && e.TargetID == "com.example.other.Base" {
			hasImport = true
		}
	}
	if !hasImport {
		t.Error("missing import edge for com.example.other.Base")
	}

	// Extends to Base from Repo class
	var hasExtends bool
	for _, e := range r.Edges {
		if e.Kind == model.EdgeExtends && e.TargetID == "Base" {
			hasExtends = true
		}
	}
	if !hasExtends {
		t.Error("missing EXTENDS edge to Base")
	}
}

func TestScalaExtendsWith(t *testing.T) {
	d := NewScalaStructuresDetector()
	ctx := &detector.Context{FilePath: "src/Service.scala", Language: "scala", Content: scalaExtendsWith}
	r := d.Detect(ctx)
	var hasExtends, hasImplements bool
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeExtends:
			if e.TargetID == "Actor" {
				hasExtends = true
			}
		case model.EdgeImplements:
			hasImplements = true
		}
	}
	if !hasExtends {
		t.Error("missing EXTENDS edge to Actor")
	}
	if !hasImplements {
		t.Error("missing IMPLEMENTS edge")
	}
}

func TestScalaStructuresNegative(t *testing.T) {
	d := NewScalaStructuresDetector()
	ctx := &detector.Context{FilePath: "src/A.scala", Language: "scala", Content: ""}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 || len(r.Edges) != 0 {
		t.Fatalf("expected empty result, got %d/%d", len(r.Nodes), len(r.Edges))
	}
}

// TestScalaStructuresNoFrameworkEmissions verifies the structures detector emits
// ONLY structural nodes (class/interface/method) on plain Scala files — no
// framework-flavored (endpoint, middleware, guard) nodes regardless of content.
func TestScalaStructuresNoFrameworkEmissions(t *testing.T) {
	d := NewScalaStructuresDetector()
	plainUtils := `package com.example.utils

object PlainUtils {
  def add(a: Int, b: Int): Int = a + b
  def greet(name: String): String = s"Hello, $name!"
}

class Counter(initial: Int) {
  private var count = initial
  def increment(): Unit = { count += 1 }
  def value: Int = count
}
`
	ctx := &detector.Context{FilePath: "PlainUtils.scala", Language: "scala", Content: plainUtils}
	r := d.Detect(ctx)
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeClass, model.NodeInterface, model.NodeMethod:
			// expected structural nodes — OK
		default:
			t.Errorf("unexpected framework node kind %q (id=%q) on plain Scala file", n.Kind, n.ID)
		}
	}
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeImports, model.EdgeExtends, model.EdgeImplements:
			// expected structural edges — OK
		default:
			t.Errorf("unexpected framework edge kind %q on plain Scala file", e.Kind)
		}
	}
}

func TestScalaStructuresDeterminism(t *testing.T) {
	d := NewScalaStructuresDetector()
	ctx := &detector.Context{FilePath: "src/A.scala", Language: "scala", Content: scalaStructuresSample}
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
			t.Fatalf("nondeterministic at %d", i)
		}
	}
}
