package typescript

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const kafkaJSSource = `const { Kafka } = require('kafkajs');
const kafka = new Kafka({ brokers: ['localhost:9092'] });

const producer = kafka.producer();
const consumer = kafka.consumer({ groupId: 'test-group' });

async function send() {
    await producer.send({ topic: 'orders', messages: [...] });
}

async function listen() {
    await consumer.subscribe({ topic: 'orders' });
    await consumer.run({ eachMessage: async ({ message }) => {} });
}
`

func TestKafkaJSPositive(t *testing.T) {
	d := NewKafkaJSDetector()
	ctx := &detector.Context{
		FilePath: "src/kafka.js",
		Language: "javascript",
		Content:  kafkaJSSource,
	}
	r := d.Detect(ctx)
	var conn, topics, events int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeDatabaseConnection:
			conn++
		case model.NodeTopic:
			topics++
		case model.NodeEvent:
			events++
		}
	}
	if conn != 1 {
		t.Errorf("expected 1 connection, got %d", conn)
	}
	if topics < 3 { // producer, consumer, topic
		t.Errorf("expected 3+ topic nodes, got %d", topics)
	}
	if events != 1 {
		t.Errorf("expected 1 event node, got %d", events)
	}
	var produces, consumes int
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeProduces:
			produces++
		case model.EdgeConsumes:
			consumes++
		}
	}
	if produces != 1 || consumes != 1 {
		t.Errorf("expected 1 produces and 1 consumes, got %d/%d", produces, consumes)
	}
}

func TestKafkaJSNegative(t *testing.T) {
	d := NewKafkaJSDetector()
	if len(d.Detect(&detector.Context{FilePath: "x.js", Content: "var x;"}).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestKafkaJSDeterminism(t *testing.T) {
	d := NewKafkaJSDetector()
	ctx := &detector.Context{FilePath: "src/x.js", Language: "javascript", Content: kafkaJSSource}
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
