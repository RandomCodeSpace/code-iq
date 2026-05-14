package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
)

const azureMessagingSample = `public class MessageService {
    ServiceBusSenderClient sender;
    public void init() {
        new ServiceBusClientBuilder().queueName("orders").buildClient();
    }
}
`

func TestAzureMessagingPositive(t *testing.T) {
	d := NewAzureMessagingDetector()
	ctx := &detector.Context{FilePath: "src/MessageService.java", Language: "java", Content: azureMessagingSample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
}

func TestAzureMessagingNegative(t *testing.T) {
	d := NewAzureMessagingDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestAzureMessagingDeterminism(t *testing.T) {
	d := NewAzureMessagingDetector()
	ctx := &detector.Context{FilePath: "src/MessageService.java", Language: "java", Content: azureMessagingSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic")
	}
}
