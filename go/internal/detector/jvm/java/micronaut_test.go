package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const micronautSample = `import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
@Controller("/api")
public class HelloController {
    @Inject
    private GreetingService greeting;
    @Get("/hello")
    public String hello() { return "hi"; }
    @Post("/echo")
    public String echo(String msg) { return msg; }
}
`

func TestMicronautPositive(t *testing.T) {
	d := NewMicronautDetector()
	ctx := &detector.Context{FilePath: "src/HelloController.java", Language: "java", Content: micronautSample}
	r := d.Detect(ctx)
	if r == nil || len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
	var hasController, hasGET, hasPOST, hasInject bool
	for _, n := range r.Nodes {
		switch {
		case n.Kind == model.NodeClass && n.Properties["path"] == "/api":
			hasController = true
		case n.Kind == model.NodeEndpoint && n.Properties["http_method"] == "GET":
			hasGET = true
		case n.Kind == model.NodeEndpoint && n.Properties["http_method"] == "POST":
			hasPOST = true
		case n.Kind == model.NodeMiddleware && n.Label == "@Inject":
			hasInject = true
		}
	}
	if !hasController {
		t.Error("missing controller class node")
	}
	if !hasGET {
		t.Error("missing GET endpoint")
	}
	if !hasPOST {
		t.Error("missing POST endpoint")
	}
	if !hasInject {
		t.Error("missing @Inject middleware")
	}
	for _, n := range r.Nodes {
		if n.Properties["framework"] != "micronaut" {
			t.Errorf("node %q missing framework=micronaut", n.Label)
		}
	}
}

func TestMicronautDiscriminator(t *testing.T) {
	// Spring code that shares @Get/@Post via Spring 6 native: must NOT match.
	d := NewMicronautDetector()
	ctx := &detector.Context{
		FilePath: "src/SpringController.java",
		Language: "java",
		Content: `import org.springframework.web.bind.annotation.GetMapping;
public class SpringController {
    @GetMapping public String get() { return "x"; }
    @Inject Service svc;
}
`,
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("Micronaut detector matched Spring code (no io.micronaut discriminator), got %d nodes", len(r.Nodes))
	}
}

func TestMicronautNegative(t *testing.T) {
	d := NewMicronautDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes on plain code, got %d", len(r.Nodes))
	}
}

func TestMicronautDeterminism(t *testing.T) {
	d := NewMicronautDetector()
	ctx := &detector.Context{FilePath: "src/HelloController.java", Language: "java", Content: micronautSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic node count: %d vs %d", len(r1.Nodes), len(r2.Nodes))
	}
}
