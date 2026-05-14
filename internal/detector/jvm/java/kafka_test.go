package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const kafkaSample = `public class KafkaService {
    @KafkaListener(topics = "orders")
    public void consume(String msg) {}
    public void produce() { kafkaTemplate.send("notifications", "hi"); }
}
`

func TestKafkaPositive(t *testing.T) {
	d := NewKafkaDetector()
	ctx := &detector.Context{FilePath: "src/KafkaService.java", Language: "java", Content: kafkaSample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
	if len(r.Edges) == 0 {
		t.Fatal("expected edges")
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
		t.Error("missing CONSUMES or PRODUCES edge")
	}
	// Topic nodes
	var hasOrders, hasNotifs bool
	for _, n := range r.Nodes {
		if n.Properties["topic"] == "orders" {
			hasOrders = true
		}
		if n.Properties["topic"] == "notifications" {
			hasNotifs = true
		}
	}
	if !hasOrders || !hasNotifs {
		t.Error("missing topic nodes")
	}
}

func TestKafkaKotlin(t *testing.T) {
	d := NewKafkaDetector()
	sample := `class OrderConsumer {
    @KafkaListener(topics = "orders")
    fun consume(msg: String) {}
}
`
	ctx := &detector.Context{FilePath: "src/OrderConsumer.kt", Language: "kotlin", Content: sample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes for kotlin")
	}
	var hasConsume bool
	for _, e := range r.Edges {
		if e.Kind == model.EdgeConsumes {
			hasConsume = true
		}
	}
	if !hasConsume {
		t.Error("missing CONSUMES edge for kotlin sample")
	}
}

func TestKafkaNegative(t *testing.T) {
	d := NewKafkaDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestKafkaDeterminism(t *testing.T) {
	d := NewKafkaDetector()
	ctx := &detector.Context{FilePath: "src/KafkaService.java", Language: "java", Content: kafkaSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic")
	}
}
