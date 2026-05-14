package frontend

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
)

func TestVueComponent_OptionsAPI(t *testing.T) {
	d := NewVueComponentDetector()
	r := d.Detect(&detector.Context{
		FilePath: "MyComp.js",
		Language: "javascript",
		Content:  "export default { name: 'MyComp' }",
	})
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 node, got %d", len(r.Nodes))
	}
	if r.Nodes[0].Label != "MyComp" {
		t.Errorf("label = %q", r.Nodes[0].Label)
	}
}

func TestVueComponent_NoMatch(t *testing.T) {
	d := NewVueComponentDetector()
	r := d.Detect(&detector.Context{FilePath: "x.js", Language: "javascript", Content: "const x = 1;"})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestVueComponent_Deterministic(t *testing.T) {
	d := NewVueComponentDetector()
	c := &detector.Context{FilePath: "x.js", Language: "javascript", Content: "export default { name: 'Comp' }"}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
