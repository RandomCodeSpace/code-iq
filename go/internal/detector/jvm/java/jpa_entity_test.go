package java

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const jpaSource = `package com.example;
import jakarta.persistence.*;

@Entity
@Table(name = "users")
public class User {
    @Id
    @Column(name = "user_id")
    private Long id;

    @Column(name = "email")
    private String email;
}
`

func TestJPAEntityPositive(t *testing.T) {
	d := NewJPAEntityDetector()
	ctx := &detector.Context{
		FilePath: "src/User.java",
		Language: "java",
		Content:  jpaSource,
	}
	r := d.Detect(ctx)
	if r == nil || len(r.Nodes) != 1 {
		t.Fatalf("expected 1 entity node, got %+v", r)
	}
	n := r.Nodes[0]
	if n.Kind != model.NodeEntity {
		t.Errorf("kind = %v, want NodeEntity", n.Kind)
	}
	if n.Label != "User" {
		t.Errorf("label = %q, want \"User\"", n.Label)
	}
	if n.Properties["table_name"] != "users" {
		t.Errorf("table_name = %v, want \"users\"", n.Properties["table_name"])
	}
	if n.Properties["framework"] != "jpa" {
		t.Errorf("framework = %v, want \"jpa\"", n.Properties["framework"])
	}
	if n.Source != "JpaEntityDetector" {
		t.Errorf("source = %q", n.Source)
	}
}

func TestJPAEntityNegative(t *testing.T) {
	d := NewJPAEntityDetector()
	ctx := &detector.Context{
		FilePath: "src/Plain.java",
		Language: "java",
		Content:  "public class Plain { }",
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestJPAEntityDeterminism(t *testing.T) {
	d := NewJPAEntityDetector()
	ctx := &detector.Context{
		FilePath: "src/User.java",
		Language: "java",
		Content:  jpaSource,
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic count")
	}
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic id at %d", i)
		}
	}
}
