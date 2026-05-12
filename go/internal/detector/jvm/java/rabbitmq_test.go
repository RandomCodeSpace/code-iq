package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const rabbitmqSample = `public class RabbitService {
    @RabbitListener(queues = "orders")
    public void receive(String msg) {}
    public void send() { rabbitTemplate.convertAndSend("exchange1", "key", "msg"); }
}
`

func TestRabbitmqPositive(t *testing.T) {
	d := NewRabbitmqDetector()
	ctx := &detector.Context{FilePath: "src/RabbitService.java", Language: "java", Content: rabbitmqSample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
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
		t.Error("missing CONSUMES or PRODUCES")
	}
}

func TestRabbitmqNegative(t *testing.T) {
	d := NewRabbitmqDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestRabbitmqDeterminism(t *testing.T) {
	d := NewRabbitmqDetector()
	ctx := &detector.Context{FilePath: "src/RabbitService.java", Language: "java", Content: rabbitmqSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic")
	}
}
