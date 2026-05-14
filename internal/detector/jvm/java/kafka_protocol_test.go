package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const kafkaProtocolSample = `public class FetchRequest extends AbstractRequest {
}
public class FetchResponse extends AbstractResponse {
}
`

func TestKafkaProtocolPositive(t *testing.T) {
	d := NewKafkaProtocolDetector()
	ctx := &detector.Context{FilePath: "src/Fetch.java", Language: "java", Content: kafkaProtocolSample}
	r := d.Detect(ctx)
	if len(r.Nodes) != 2 {
		t.Fatalf("expected 2 nodes, got %d", len(r.Nodes))
	}
	if len(r.Edges) != 2 {
		t.Fatalf("expected 2 extends edges, got %d", len(r.Edges))
	}
	for _, n := range r.Nodes {
		if n.Kind != model.NodeProtocolMessage {
			t.Errorf("expected ProtocolMessage kind, got %v", n.Kind)
		}
	}
}

func TestKafkaProtocolNegative(t *testing.T) {
	d := NewKafkaProtocolDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestKafkaProtocolDeterminism(t *testing.T) {
	d := NewKafkaProtocolDetector()
	ctx := &detector.Context{FilePath: "src/Fetch.java", Language: "java", Content: kafkaProtocolSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic count")
	}
}
