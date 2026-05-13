package cpp

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const cppSource = `#include <iostream>
#include "myheader.h"

namespace mylib {
}

class Animal {
};

class Dog : public Animal {
};

struct Point {
    int x;
    int y;
};

enum Color {
    Red,
    Green
};

enum class Mode {
    Auto,
    Manual
};

int add(int a, int b) {
    return a + b;
}

void greet(const std::string &name) {
    std::cout << "hi" << std::endl;
}
`

const cppForwardDeclSource = `class Forward;

class Real {
};
`

func TestCppStructuresPositive(t *testing.T) {
	d := NewStructuresDetector()
	r := d.Detect(&detector.Context{FilePath: "src/main.cpp", Language: "cpp", Content: cppSource})

	kinds := map[model.NodeKind]int{}
	for _, n := range r.Nodes {
		kinds[n.Kind]++
	}
	// 1 namespace + 1 file-anchor = 2 MODULE nodes
	if kinds[model.NodeModule] != 2 {
		t.Errorf("expected 2 MODULE (namespace + file anchor), got %d", kinds[model.NodeModule])
	}
	// 2 #includes → 2 external header anchor nodes
	if kinds[model.NodeExternal] != 2 {
		t.Errorf("expected 2 EXTERNAL (include targets), got %d", kinds[model.NodeExternal])
	}
	// Classes + structs both go to CLASS — 2 classes + 1 struct + 1
	// false-positive from "enum class Mode" matching CLASS_RE (Java parity
	// bug — the same regex matches "class Mode" inside "enum class Mode").
	if kinds[model.NodeClass] != 4 {
		t.Errorf("expected 4 CLASS (Animal, Dog, Point, Mode dup), got %d", kinds[model.NodeClass])
	}
	if kinds[model.NodeEnum] < 1 {
		t.Errorf("expected >=1 ENUM, got %d", kinds[model.NodeEnum])
	}
	if kinds[model.NodeMethod] < 1 {
		t.Errorf("expected >=1 METHOD, got %d", kinds[model.NodeMethod])
	}

	// 2 includes
	imports := 0
	extends := 0
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeImports:
			imports++
		case model.EdgeExtends:
			extends++
		}
	}
	if imports != 2 {
		t.Errorf("expected 2 includes, got %d", imports)
	}
	if extends != 1 {
		t.Errorf("expected 1 EXTENDS (Dog -> Animal), got %d", extends)
	}
}

func TestCppStructuresForwardDeclarationsSkipped(t *testing.T) {
	d := NewStructuresDetector()
	r := d.Detect(&detector.Context{FilePath: "x.cpp", Language: "cpp", Content: cppForwardDeclSource})
	classes := 0
	for _, n := range r.Nodes {
		if n.Kind == model.NodeClass {
			classes++
			if n.Label == "Forward" {
				t.Error("Forward declaration should be skipped")
			}
		}
	}
	if classes != 1 {
		t.Errorf("expected 1 class (Real), got %d", classes)
	}
}

func TestCppStructuresNegative(t *testing.T) {
	d := NewStructuresDetector()
	r := d.Detect(&detector.Context{FilePath: "x.cpp", Language: "cpp", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestCppStructuresDeterminism(t *testing.T) {
	d := NewStructuresDetector()
	ctx := &detector.Context{FilePath: "src/main.cpp", Language: "cpp", Content: cppSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
