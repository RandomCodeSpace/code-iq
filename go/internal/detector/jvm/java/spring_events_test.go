package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const springEventsSample = `public class EventService {
    @EventListener
    public void handle(OrderEvent event) {}
    public void publish() {
        applicationEventPublisher.publishEvent(new OrderEvent());
    }
}
`

func TestSpringEventsPositive(t *testing.T) {
	d := NewSpringEventsDetector()
	ctx := &detector.Context{FilePath: "src/EventService.java", Language: "java", Content: springEventsSample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
	if len(r.Edges) == 0 {
		t.Fatal("expected edges")
	}
	var hasListens, hasPublishes bool
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeListens:
			hasListens = true
		case model.EdgePublishes:
			hasPublishes = true
		}
	}
	if !hasListens {
		t.Error("missing LISTENS edge")
	}
	if !hasPublishes {
		t.Error("missing PUBLISHES edge")
	}
	for _, n := range r.Nodes {
		if n.Properties["framework"] != "spring_boot" {
			t.Errorf("node %q missing framework=spring_boot", n.Label)
		}
	}
}

func TestSpringEventsNegative(t *testing.T) {
	d := NewSpringEventsDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestSpringEventsDeterminism(t *testing.T) {
	d := NewSpringEventsDetector()
	ctx := &detector.Context{FilePath: "src/EventService.java", Language: "java", Content: springEventsSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatalf("nondeterministic")
	}
}
