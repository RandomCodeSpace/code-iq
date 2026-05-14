package rust

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const rustStructSource = `use std::io;
use serde::Serialize;

pub mod handlers;
mod private_mod;

pub struct User {
    name: String,
}

pub trait Greet {
    fn say_hi(&self);
}

pub enum Status {
    Ok,
    Err,
}

impl Greet for User {
    fn say_hi(&self) {}
}

impl User {
    pub fn new() -> Self {
        Self { name: String::new() }
    }
}

pub fn create_user() -> User {
    User::new()
}

macro_rules! my_macro {
    () => {};
}
`

func TestRustStructuresPositive(t *testing.T) {
	d := NewStructuresDetector()
	r := d.Detect(&detector.Context{FilePath: "src/lib.rs", Language: "rust", Content: rustStructSource})

	kinds := map[model.NodeKind]int{}
	for _, n := range r.Nodes {
		kinds[n.Kind]++
	}
	// 2 mod declarations + 1 file-anchor module node = 3 MODULE nodes
	if kinds[model.NodeModule] != 3 {
		t.Errorf("expected 3 MODULE (2 mods + file anchor), got %d", kinds[model.NodeModule])
	}
	// 2 use imports → 2 external module anchor nodes
	if kinds[model.NodeExternal] != 2 {
		t.Errorf("expected 2 EXTERNAL (use targets), got %d", kinds[model.NodeExternal])
	}
	if kinds[model.NodeClass] < 1 {
		t.Errorf("expected >=1 CLASS (struct), got %d", kinds[model.NodeClass])
	}
	if kinds[model.NodeInterface] < 1 {
		t.Errorf("expected >=1 INTERFACE (trait), got %d", kinds[model.NodeInterface])
	}
	if kinds[model.NodeEnum] < 1 {
		t.Errorf("expected >=1 ENUM, got %d", kinds[model.NodeEnum])
	}
	if kinds[model.NodeMethod] < 1 {
		t.Errorf("expected >=1 METHOD, got %d", kinds[model.NodeMethod])
	}

	// 2 use edges + 1 IMPLEMENTS edge (Greet for User) + 1 DEFINES edge (impl User)
	importEdges := 0
	implementsEdges := 0
	definesEdges := 0
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeImports:
			importEdges++
		case model.EdgeImplements:
			implementsEdges++
		case model.EdgeDefines:
			definesEdges++
		}
	}
	if importEdges != 2 {
		t.Errorf("expected 2 import edges, got %d", importEdges)
	}
	if implementsEdges != 1 {
		t.Errorf("expected 1 IMPLEMENTS edge, got %d", implementsEdges)
	}
	if definesEdges != 1 {
		t.Errorf("expected 1 DEFINES edge from inherent impl, got %d", definesEdges)
	}
}

func TestRustStructuresMacro(t *testing.T) {
	d := NewStructuresDetector()
	r := d.Detect(&detector.Context{FilePath: "lib.rs", Language: "rust", Content: rustStructSource})
	found := false
	for _, n := range r.Nodes {
		if n.Label == "my_macro!" {
			found = true
			if n.Properties["type"] != "macro" {
				t.Errorf("type = %v", n.Properties["type"])
			}
		}
	}
	if !found {
		t.Error("expected macro node")
	}
}

func TestRustStructuresNegative(t *testing.T) {
	d := NewStructuresDetector()
	r := d.Detect(&detector.Context{FilePath: "x.rs", Language: "rust", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestRustStructuresDeterminism(t *testing.T) {
	d := NewStructuresDetector()
	ctx := &detector.Context{FilePath: "src/lib.rs", Language: "rust", Content: rustStructSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
