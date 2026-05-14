package frontend

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
)

func TestAngularComponent_Positive(t *testing.T) {
	code := "@Component({\n  selector: 'app-root'\n})\nexport class AppComponent {}"
	d := NewAngularComponentDetector()
	r := d.Detect(&detector.Context{FilePath: "app.component.ts", Language: "typescript", Content: code})
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 node, got %d", len(r.Nodes))
	}
	if r.Nodes[0].Properties["framework"] != "angular" {
		t.Errorf("framework = %v", r.Nodes[0].Properties["framework"])
	}
}

func TestAngularComponent_NoMatch(t *testing.T) {
	d := NewAngularComponentDetector()
	r := d.Detect(&detector.Context{FilePath: "x.ts", Language: "typescript", Content: "class Foo {}"})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestAngularComponent_Deterministic(t *testing.T) {
	code := "@Component({\n  selector: 'app-root'\n})\nclass AppComponent {}"
	d := NewAngularComponentDetector()
	c := &detector.Context{FilePath: "x.ts", Language: "typescript", Content: code}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
