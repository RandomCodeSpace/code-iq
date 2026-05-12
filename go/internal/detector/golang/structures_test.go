package golang

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const goStructSource = `package server

import (
	"net/http"
	"fmt"
)

import "errors"

type Server struct {
	Name string
}

type Repository interface {
	Save() error
}

func (s *Server) Start() error {
	return nil
}

func (s Server) name() string {
	return s.Name
}

func NewServer() *Server {
	return &Server{}
}

func helper() {
	fmt.Println("hi")
}
`

func TestGoStructuresPositive(t *testing.T) {
	d := NewStructuresDetector()
	ctx := &detector.Context{
		FilePath: "server/server.go",
		Language: "go",
		Content:  goStructSource,
	}
	r := d.Detect(ctx)
	if r == nil {
		t.Fatal("nil result")
	}

	// Expect: 1 module + 1 struct + 1 interface + 2 methods + 2 functions = 7 nodes
	kindCounts := map[model.NodeKind]int{}
	for _, n := range r.Nodes {
		kindCounts[n.Kind]++
	}
	if kindCounts[model.NodeModule] != 1 {
		t.Errorf("expected 1 MODULE, got %d", kindCounts[model.NodeModule])
	}
	if kindCounts[model.NodeClass] != 1 {
		t.Errorf("expected 1 CLASS, got %d", kindCounts[model.NodeClass])
	}
	if kindCounts[model.NodeInterface] != 1 {
		t.Errorf("expected 1 INTERFACE, got %d", kindCounts[model.NodeInterface])
	}
	if kindCounts[model.NodeMethod] != 4 {
		t.Errorf("expected 4 METHOD (2 receiver + 2 func), got %d", kindCounts[model.NodeMethod])
	}

	// Imports: 2 block ("net/http", "fmt") + 1 single ("errors") = 3
	imports := 0
	for _, e := range r.Edges {
		if e.Kind == model.EdgeImports {
			imports++
		}
	}
	if imports != 3 {
		t.Errorf("expected 3 imports edges, got %d", imports)
	}
}

func TestGoStructuresNegative(t *testing.T) {
	d := NewStructuresDetector()
	r := d.Detect(&detector.Context{FilePath: "x.go", Language: "go", Content: ""})
	if len(r.Nodes) != 0 || len(r.Edges) != 0 {
		t.Fatal("expected empty result")
	}
}

func TestGoStructuresDeterminism(t *testing.T) {
	d := NewStructuresDetector()
	ctx := &detector.Context{
		FilePath: "server/server.go",
		Language: "go",
		Content:  goStructSource,
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
	sort.Slice(r1.Nodes, func(i, j int) bool { return r1.Nodes[i].ID < r1.Nodes[j].ID })
	sort.Slice(r2.Nodes, func(i, j int) bool { return r2.Nodes[i].ID < r2.Nodes[j].ID })
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic id at %d", i)
		}
	}
}

func TestGoStructuresExportedFlag(t *testing.T) {
	d := NewStructuresDetector()
	r := d.Detect(&detector.Context{
		FilePath: "x.go",
		Language: "go",
		Content:  goStructSource,
	})
	for _, n := range r.Nodes {
		if n.Kind == model.NodeClass && n.Label == "Server" {
			if n.Properties["exported"] != true {
				t.Errorf("Server should be exported, got %v", n.Properties["exported"])
			}
		}
		if n.Kind == model.NodeMethod && n.Label == "Server.name" {
			if n.Properties["exported"] != false {
				t.Errorf("name() should be unexported, got %v", n.Properties["exported"])
			}
		}
	}
}
