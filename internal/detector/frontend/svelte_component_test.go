package frontend

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
)

func TestSvelteComponent_WithProps(t *testing.T) {
	d := NewSvelteComponentDetector()
	src := "<script>\nexport let count = 0;\nexport let name;\n</script>\n<p>{count}</p>\n"
	r := d.Detect(&detector.Context{FilePath: "components/Counter.svelte", Language: "svelte", Content: src})
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 node, got %d", len(r.Nodes))
	}
	if r.Nodes[0].Label != "Counter" {
		t.Errorf("label = %q want Counter", r.Nodes[0].Label)
	}
	props, ok := r.Nodes[0].Properties["props"].([]string)
	if !ok || len(props) != 2 {
		t.Errorf("expected 2 props, got %v", r.Nodes[0].Properties["props"])
	}
}

func TestSvelteComponent_Reactive(t *testing.T) {
	d := NewSvelteComponentDetector()
	src := "<script>\nlet x = 1;\n$: doubled = x * 2;\n</script>"
	r := d.Detect(&detector.Context{FilePath: "X.svelte", Language: "svelte", Content: src})
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 node, got %d", len(r.Nodes))
	}
	if rc, _ := r.Nodes[0].Properties["reactive_statements"].(int); rc != 1 {
		t.Errorf("reactive_statements = %v want 1", r.Nodes[0].Properties["reactive_statements"])
	}
}

func TestSvelteComponent_NoMatch(t *testing.T) {
	d := NewSvelteComponentDetector()
	r := d.Detect(&detector.Context{FilePath: "x.js", Language: "svelte", Content: "const x = 1;"})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestSvelteComponent_Deterministic(t *testing.T) {
	d := NewSvelteComponentDetector()
	c := &detector.Context{FilePath: "X.svelte", Language: "svelte", Content: "<script>\nexport let x;\n$: y = x * 2;\n</script>"}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
