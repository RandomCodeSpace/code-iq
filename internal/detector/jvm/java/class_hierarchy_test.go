package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const classHierarchySample = `public abstract class Animal implements Serializable {
}
public class Dog extends Animal implements Comparable {
}
public interface Flyable extends Moveable {
}
public enum Color implements Coded {
}
public @interface MyAnnotation {
}
`

func TestClassHierarchyPositive(t *testing.T) {
	d := NewClassHierarchyDetector()
	ctx := &detector.Context{FilePath: "src/H.java", Language: "java", Content: classHierarchySample}
	r := d.Detect(ctx)
	if len(r.Nodes) != 5 {
		t.Fatalf("expected 5 nodes (abstract+class+interface+enum+annotation), got %d", len(r.Nodes))
	}
	var hasAbstract, hasClass, hasInterface, hasEnum, hasAnno bool
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeAbstractClass:
			if n.Label == "Animal" {
				hasAbstract = true
			}
		case model.NodeClass:
			if n.Label == "Dog" {
				hasClass = true
			}
		case model.NodeInterface:
			if n.Label == "Flyable" {
				hasInterface = true
			}
		case model.NodeEnum:
			if n.Label == "Color" {
				hasEnum = true
			}
		case model.NodeAnnotationType:
			if n.Label == "MyAnnotation" {
				hasAnno = true
			}
		}
	}
	if !hasAbstract {
		t.Error("missing Animal abstract")
	}
	if !hasClass {
		t.Error("missing Dog class")
	}
	if !hasInterface {
		t.Error("missing Flyable interface")
	}
	if !hasEnum {
		t.Error("missing Color enum")
	}
	if !hasAnno {
		t.Error("missing MyAnnotation annotation type")
	}
	if len(r.Edges) == 0 {
		t.Error("expected at least one EXTENDS or IMPLEMENTS edge")
	}
}

func TestClassHierarchyNegative(t *testing.T) {
	d := NewClassHierarchyDetector()
	ctx := &detector.Context{FilePath: "src/Plain.java", Language: "java", Content: ""}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes on empty input, got %d", len(r.Nodes))
	}
}

func TestClassHierarchyDeterminism(t *testing.T) {
	d := NewClassHierarchyDetector()
	ctx := &detector.Context{FilePath: "src/H.java", Language: "java", Content: classHierarchySample}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatalf("nondeterministic count: %d vs %d", len(r1.Nodes), len(r2.Nodes))
	}
}
