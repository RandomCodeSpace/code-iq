package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
)

const ibmMqSample = `public class MqService {
    public void connect() {
        MQQueueManager qm = new MQQueueManager("QM1");
        qm.accessQueue("ORDERS.QUEUE", openOptions);
        queue.put(msg);
    }
}
`

func TestIbmMqPositive(t *testing.T) {
	d := NewIbmMqDetector()
	ctx := &detector.Context{FilePath: "src/MqService.java", Language: "java", Content: ibmMqSample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 || len(r.Edges) == 0 {
		t.Fatal("expected nodes + edges")
	}
}

func TestIbmMqNegative(t *testing.T) {
	d := NewIbmMqDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestIbmMqDeterminism(t *testing.T) {
	d := NewIbmMqDetector()
	ctx := &detector.Context{FilePath: "src/MqService.java", Language: "java", Content: ibmMqSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic")
	}
}
