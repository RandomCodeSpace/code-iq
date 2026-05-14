package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const pomSample = `<project>
    <groupId>com.example</groupId>
    <artifactId>my-app</artifactId>
    <modules>
        <module>core</module>
    </modules>
    <dependencies>
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-core</artifactId>
        </dependency>
    </dependencies>
</project>
`

func TestModuleDepsMaven(t *testing.T) {
	d := NewModuleDepsDetector()
	ctx := &detector.Context{FilePath: "pom.xml", Language: "xml", Content: pomSample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected nodes")
	}
	if len(r.Edges) == 0 {
		t.Fatal("expected edges")
	}
	var hasMain, hasSub bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeModule && n.Label == "my-app" {
			hasMain = true
		}
		if n.Label == "core" {
			hasSub = true
		}
	}
	if !hasMain {
		t.Error("missing main module my-app")
	}
	if !hasSub {
		t.Error("missing submodule core")
	}
	// CONTAINS edge to core, DEPENDS_ON edge to spring-core
	var hasContains, hasDepends bool
	for _, e := range r.Edges {
		if e.Kind == model.EdgeContains {
			hasContains = true
		}
		if e.Kind == model.EdgeDependsOn {
			hasDepends = true
		}
	}
	if !hasContains {
		t.Error("missing CONTAINS edge")
	}
	if !hasDepends {
		t.Error("missing DEPENDS_ON edge")
	}
}

func TestModuleDepsGradle(t *testing.T) {
	sample := `dependencies {
    implementation 'org.springframework:spring-core:6.0.0'
    implementation project(':core')
}
`
	d := NewModuleDepsDetector()
	ctx := &detector.Context{FilePath: "service/build.gradle", Language: "gradle", Content: sample}
	r := d.Detect(ctx)
	if len(r.Nodes) == 0 {
		t.Fatal("expected at least one module node")
	}
	var hasProjDep, hasExtDep bool
	for _, e := range r.Edges {
		if e.Kind != model.EdgeDependsOn {
			continue
		}
		if e.Properties["type"] == "project" {
			hasProjDep = true
		}
		if e.Properties["type"] == "external" {
			hasExtDep = true
		}
	}
	if !hasProjDep {
		t.Error("missing project dep edge")
	}
	if !hasExtDep {
		t.Error("missing external dep edge")
	}
}

func TestModuleDepsSettingsGradle(t *testing.T) {
	sample := `include ':core'
include ':api'
`
	d := NewModuleDepsDetector()
	ctx := &detector.Context{FilePath: "settings.gradle", Language: "gradle", Content: sample}
	r := d.Detect(ctx)
	if len(r.Nodes) != 2 {
		t.Fatalf("expected 2 module nodes from settings.gradle, got %d", len(r.Nodes))
	}
}

func TestModuleDepsNegative(t *testing.T) {
	d := NewModuleDepsDetector()
	ctx := &detector.Context{FilePath: "src/Foo.java", Language: "java", Content: "public class Foo {}"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes on non-build file, got %d", len(r.Nodes))
	}
}

func TestModuleDepsDeterminism(t *testing.T) {
	d := NewModuleDepsDetector()
	ctx := &detector.Context{FilePath: "pom.xml", Language: "xml", Content: pomSample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic")
	}
}
