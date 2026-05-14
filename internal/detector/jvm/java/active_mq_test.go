package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
)

const activeMqSample = `import org.apache.activemq.ActiveMQConnectionFactory;
public class AmqService {
    ActiveMQConnectionFactory factory = new ActiveMQConnectionFactory("tcp://broker:61616");
    public void run() {
        ActiveMQQueue q = new ActiveMQQueue("ORDERS");
        producer.send(msg);
    }
}
`

func TestActiveMqPositive(t *testing.T) {
	d := NewActiveMqDetector()
	ctx := &detector.Context{FilePath: "src/AmqService.java", Language: "java", Content: activeMqSample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
}

func TestActiveMqArtemisDiscriminator(t *testing.T) {
	sample := `import org.apache.activemq.artemis.ActiveMQConnectionFactory;
public class ArtemisService {
    ActiveMQConnectionFactory f = new ActiveMQConnectionFactory("tcp://broker:61616");
}
`
	d := NewActiveMqDetector()
	ctx := &detector.Context{FilePath: "src/ArtemisService.java", Language: "java", Content: sample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
	var hasArtemis bool
	for _, n := range r.Nodes {
		if n.Properties["broker"] == "activemq_artemis" {
			hasArtemis = true
		}
	}
	if !hasArtemis {
		t.Error("expected broker=activemq_artemis when import is org.apache.activemq.artemis")
	}
}

func TestActiveMqNegative(t *testing.T) {
	d := NewActiveMqDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestActiveMqDeterminism(t *testing.T) {
	d := NewActiveMqDetector()
	ctx := &detector.Context{FilePath: "src/AmqService.java", Language: "java", Content: activeMqSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic")
	}
}
