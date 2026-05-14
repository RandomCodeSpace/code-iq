package java

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
)

const springRestSource = `package com.example;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users")
public class UserController {
    @GetMapping("/{id}")
    public User get(@PathVariable Long id) { return null; }

    @PostMapping
    public User create(@RequestBody User u) { return u; }

    @ModelAttribute
    public void populate() { } // should NOT be detected as endpoint
}
`

func TestSpringRestPositive(t *testing.T) {
	d := NewSpringRestDetector()
	ctx := &detector.Context{
		FilePath: "src/UserController.java",
		Language: "java",
		Content:  springRestSource,
	}
	r := d.Detect(ctx)
	if r == nil {
		t.Fatal("Detect returned nil")
	}
	if len(r.Nodes) != 2 {
		t.Fatalf("expected 2 ENDPOINT nodes, got %d: %+v", len(r.Nodes), r.Nodes)
	}
	// Sort by label for stable assertion.
	sort.Slice(r.Nodes, func(i, j int) bool { return r.Nodes[i].Label < r.Nodes[j].Label })
	if r.Nodes[0].Properties["http_method"] != "POST" {
		t.Errorf("expected POST, got %v", r.Nodes[0].Properties["http_method"])
	}
	if r.Nodes[1].Properties["http_method"] != "GET" {
		t.Errorf("expected GET, got %v", r.Nodes[1].Properties["http_method"])
	}
	for _, n := range r.Nodes {
		if n.Properties["framework"] != "spring_boot" {
			t.Errorf("framework property missing or wrong: %v", n.Properties)
		}
		if n.Source != "SpringRestDetector" {
			t.Errorf("source = %q, want SpringRestDetector", n.Source)
		}
	}
}

func TestSpringRestNegative(t *testing.T) {
	d := NewSpringRestDetector()
	ctx := &detector.Context{
		FilePath: "src/Plain.java",
		Language: "java",
		Content:  "public class Plain { public void noop() { } }",
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes on plain class, got %d", len(r.Nodes))
	}
}

func TestSpringRestDeterminism(t *testing.T) {
	d := NewSpringRestDetector()
	ctx := &detector.Context{
		FilePath: "src/UserController.java",
		Language: "java",
		Content:  springRestSource,
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic node count")
	}
	sort.Slice(r1.Nodes, func(i, j int) bool { return r1.Nodes[i].ID < r1.Nodes[j].ID })
	sort.Slice(r2.Nodes, func(i, j int) bool { return r2.Nodes[i].ID < r2.Nodes[j].ID })
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID || r1.Nodes[i].Label != r2.Nodes[i].Label {
			t.Fatalf("non-deterministic: run1=%+v run2=%+v", r1.Nodes[i], r2.Nodes[i])
		}
	}
}
