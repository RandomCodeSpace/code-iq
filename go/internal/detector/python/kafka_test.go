package python

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const kafkaPySource = `from kafka import KafkaProducer, KafkaConsumer

producer = KafkaProducer(bootstrap_servers='localhost:9092')
consumer = KafkaConsumer(bootstrap_servers='localhost:9092')

producer.send('orders', b'hello')
consumer.subscribe(['orders'])
`

func TestKafkaPythonPositive(t *testing.T) {
	d := NewKafkaPythonDetector()
	ctx := &detector.Context{
		FilePath: "app/k.py",
		Language: "python",
		Content:  kafkaPySource,
	}
	r := d.Detect(ctx)
	var topics int
	for _, n := range r.Nodes {
		if n.Kind == model.NodeTopic {
			topics++
		}
	}
	if topics < 3 { // producer, consumer, topic
		t.Errorf("expected at least 3 topic nodes, got %d", topics)
	}
	var produces, consumes, imports int
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeProduces:
			produces++
		case model.EdgeConsumes:
			consumes++
		case model.EdgeImports:
			imports++
		}
	}
	if produces != 1 || consumes != 1 {
		t.Errorf("expected 1/1 produces/consumes, got %d/%d", produces, consumes)
	}
	if imports < 1 {
		t.Errorf("expected at least 1 import edge")
	}
}

func TestKafkaPythonNegative(t *testing.T) {
	d := NewKafkaPythonDetector()
	if len(d.Detect(&detector.Context{FilePath: "x.py", Content: "x = 1"}).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestKafkaPythonDeterminism(t *testing.T) {
	d := NewKafkaPythonDetector()
	ctx := &detector.Context{FilePath: "app/k.py", Language: "python", Content: kafkaPySource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
	sort.Slice(r1.Nodes, func(i, j int) bool { return r1.Nodes[i].ID < r1.Nodes[j].ID })
	sort.Slice(r2.Nodes, func(i, j int) bool { return r2.Nodes[i].ID < r2.Nodes[j].ID })
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic at %d", i)
		}
	}
}
