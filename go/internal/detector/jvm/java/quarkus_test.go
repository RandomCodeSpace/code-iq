package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const quarkusSample = `import io.quarkus.runtime.annotations.ConfigProperty;
@ApplicationScoped
public class GreetingService {
    @ConfigProperty(name = "greeting.message")
    String message;
    @Scheduled(every = "10s")
    public void tick() {}
}
`

func TestQuarkusPositive(t *testing.T) {
	d := NewQuarkusDetector()
	ctx := &detector.Context{FilePath: "src/Greeting.java", Language: "java", Content: quarkusSample}
	r := d.Detect(ctx)
	if r == nil || len(r.Nodes) == 0 {
		t.Fatal("expected nodes, got none")
	}
	var hasConfig, hasScheduled, hasScope bool
	for _, n := range r.Nodes {
		switch {
		case n.Kind == model.NodeConfigKey && n.Properties["config_key"] == "greeting.message":
			hasConfig = true
		case n.Kind == model.NodeEvent && n.Properties["schedule"] == "10s":
			hasScheduled = true
		case n.Kind == model.NodeMiddleware && n.Properties["cdi_scope"] == "ApplicationScoped":
			hasScope = true
		}
	}
	if !hasConfig {
		t.Error("missing @ConfigProperty node")
	}
	if !hasScheduled {
		t.Error("missing @Scheduled event node")
	}
	if !hasScope {
		t.Error("missing @ApplicationScoped CDI node")
	}
	// All nodes should have framework=quarkus
	for _, n := range r.Nodes {
		if n.Properties["framework"] != "quarkus" {
			t.Errorf("node %q missing framework=quarkus", n.Label)
		}
	}
}

func TestQuarkusDiscriminator(t *testing.T) {
	// Spring Boot code that shares annotations (@Transactional, @Scheduled) but
	// has no Quarkus import → must NOT be detected by QuarkusDetector.
	d := NewQuarkusDetector()
	ctx := &detector.Context{
		FilePath: "src/SpringService.java",
		Language: "java",
		Content: `import org.springframework.stereotype.Service;
@Service
public class SpringService {
    @Transactional
    @Scheduled(fixedRate = 1000)
    public void run() {}
}
`,
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("Quarkus detector matched Spring code (no io.quarkus discriminator), got %d nodes", len(r.Nodes))
	}
}

func TestQuarkusNegative(t *testing.T) {
	d := NewQuarkusDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes on plain code, got %d", len(r.Nodes))
	}
}

func TestQuarkusDeterminism(t *testing.T) {
	d := NewQuarkusDetector()
	ctx := &detector.Context{FilePath: "src/Greeting.java", Language: "java", Content: quarkusSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic count: %d vs %d", len(r1.Nodes), len(r2.Nodes))
	}
}
