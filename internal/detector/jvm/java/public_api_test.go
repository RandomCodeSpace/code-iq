package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
)

const publicApiSample = `public class UserService {
    public User findUser(String name) { return null; }
    protected void process(Order order) {}
    private void internal() {}
    public String getName() { return name; }
}
`

func TestPublicApiPositive(t *testing.T) {
	d := NewPublicApiDetector()
	ctx := &detector.Context{FilePath: "src/UserService.java", Language: "java", Content: publicApiSample}
	r := d.Detect(ctx)
	if len(r.Nodes) != 2 {
		t.Fatalf("expected 2 methods (findUser + process), got %d: %+v", len(r.Nodes), r.Nodes)
	}
	if len(r.Edges) != 2 {
		t.Fatalf("expected 2 DEFINES edges, got %d", len(r.Edges))
	}
}

func TestPublicApiNegative(t *testing.T) {
	d := NewPublicApiDetector()
	ctx := &detector.Context{FilePath: "src/Foo.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestPublicApiDeterminism(t *testing.T) {
	d := NewPublicApiDetector()
	ctx := &detector.Context{FilePath: "src/UserService.java", Language: "java", Content: publicApiSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic count")
	}
}
