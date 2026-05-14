package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const jaxrsSample = `@Path("/api/users")
public class UserResource {
    @GET
    @Path("/{id}")
    public User getUser(@PathParam("id") Long id) { return null; }
}
`

func TestJaxrsPositive(t *testing.T) {
	d := NewJaxrsDetector()
	ctx := &detector.Context{FilePath: "src/UserResource.java", Language: "java", Content: jaxrsSample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
	var found bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeEndpoint && n.Properties["http_method"] == "GET" {
			found = true
			if n.Properties["path"] != "/api/users/{id}" {
				t.Errorf("expected path /api/users/{id}, got %v", n.Properties["path"])
			}
		}
	}
	if !found {
		t.Error("missing GET endpoint")
	}
}

func TestJaxrsNegative(t *testing.T) {
	d := NewJaxrsDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestJaxrsDeterminism(t *testing.T) {
	d := NewJaxrsDetector()
	ctx := &detector.Context{FilePath: "src/UserResource.java", Language: "java", Content: jaxrsSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic")
	}
}
