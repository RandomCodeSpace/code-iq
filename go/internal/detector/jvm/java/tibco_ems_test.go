package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
)

const tibcoSample = `public class EmsService {
    TibjmsConnectionFactory factory = new TibjmsConnectionFactory();
    public void setup() {
        factory.setServerUrl("tcp://ems-server:7222");
        session.createQueue("ORDER.QUEUE");
        producer.send(msg);
    }
}
`

func TestTibcoEmsPositive(t *testing.T) {
	d := NewTibcoEmsDetector()
	ctx := &detector.Context{FilePath: "src/EmsService.java", Language: "java", Content: tibcoSample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
}

func TestTibcoEmsNegative(t *testing.T) {
	d := NewTibcoEmsDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestTibcoEmsDeterminism(t *testing.T) {
	d := NewTibcoEmsDetector()
	ctx := &detector.Context{FilePath: "src/EmsService.java", Language: "java", Content: tibcoSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic")
	}
}
