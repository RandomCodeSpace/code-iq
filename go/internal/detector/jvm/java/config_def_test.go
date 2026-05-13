package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const configDefKafkaSample = `public class MyConfig {
    static ConfigDef CONFIG = new ConfigDef()
        .define("my.setting.name", Type.STRING, "default")
        .define("my.setting.port", Type.INT, 8080);
}
`

func TestConfigDefKafka(t *testing.T) {
	d := NewConfigDefDetector()
	ctx := &detector.Context{FilePath: "src/MyConfig.java", Language: "java", Content: configDefKafkaSample}
	r := d.Detect(ctx)
	if len(r.Nodes) != 2 {
		t.Fatalf("expected 2 nodes, got %d: %+v", len(r.Nodes), r.Nodes)
	}
	if len(r.Edges) != 2 {
		t.Fatalf("expected 2 reads_config edges, got %d", len(r.Edges))
	}
	for _, n := range r.Nodes {
		if n.Kind != model.NodeConfigDefinition {
			t.Errorf("expected ConfigDefinition kind, got %v", n.Kind)
		}
	}
}

func TestConfigDefSpringValue(t *testing.T) {
	sample := `import org.springframework.beans.factory.annotation.Value;
public class AppConfig {
    @Value("${app.timeout}")
    private int timeout;
    @Value("${app.host}")
    private String host;
}
`
	d := NewConfigDefDetector()
	ctx := &detector.Context{FilePath: "src/AppConfig.java", Language: "java", Content: sample}
	r := d.Detect(ctx)
	if len(r.Nodes) != 2 {
		t.Fatalf("expected 2 @Value nodes, got %d", len(r.Nodes))
	}
	var hasTimeout, hasHost bool
	for _, n := range r.Nodes {
		if n.Label == "app.timeout" {
			hasTimeout = true
		}
		if n.Label == "app.host" {
			hasHost = true
		}
	}
	if !hasTimeout || !hasHost {
		t.Error("missing one of the @Value nodes")
	}
}

func TestConfigDefConfigurationProperties(t *testing.T) {
	sample := `import org.springframework.boot.context.properties.ConfigurationProperties;
@ConfigurationProperties(prefix = "spring.datasource")
public class DataSourceConfig {
    private String url;
}
`
	d := NewConfigDefDetector()
	ctx := &detector.Context{FilePath: "src/DataSourceConfig.java", Language: "java", Content: sample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
	var hasPrefix bool
	for _, n := range r.Nodes {
		if n.Label == "spring.datasource" {
			hasPrefix = true
		}
	}
	if !hasPrefix {
		t.Error("missing spring.datasource prefix node")
	}
}

func TestConfigDefNegative(t *testing.T) {
	d := NewConfigDefDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestConfigDefDeterminism(t *testing.T) {
	d := NewConfigDefDetector()
	ctx := &detector.Context{FilePath: "src/MyConfig.java", Language: "java", Content: configDefKafkaSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic")
	}
}
