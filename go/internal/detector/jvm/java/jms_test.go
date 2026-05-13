package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const jmsSample = `public class JmsService {
    @JmsListener(destination = "orders.queue")
    public void receive(String msg) {}
    public void send() { jmsTemplate.send("reply.queue", msg); }
}
`

func TestJmsPositive(t *testing.T) {
	d := NewJmsDetector()
	ctx := &detector.Context{FilePath: "src/JmsService.java", Language: "java", Content: jmsSample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 || len(r.Edges) == 0 {
		t.Fatal("expected nodes + edges")
	}
	var hasConsume, hasProduce bool
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeConsumes:
			hasConsume = true
		case model.EdgeProduces:
			hasProduce = true
		}
	}
	if !hasConsume || !hasProduce {
		t.Error("missing JMS CONSUMES or PRODUCES")
	}
}

func TestJmsNegative(t *testing.T) {
	d := NewJmsDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestJmsDeterminism(t *testing.T) {
	d := NewJmsDetector()
	ctx := &detector.Context{FilePath: "src/JmsService.java", Language: "java", Content: jmsSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic")
	}
}
