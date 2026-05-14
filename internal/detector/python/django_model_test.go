package python

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const djangoSource = `from django.db import models

class Author(models.Model):
    name = models.CharField(max_length=100)

    class Meta:
        db_table = "authors"

class Book(models.Model):
    title = models.CharField(max_length=200)
    author = models.ForeignKey(Author, on_delete=models.CASCADE)
`

func TestDjangoModelPositive(t *testing.T) {
	d := NewDjangoModelDetector()
	ctx := &detector.Context{
		FilePath: "app/models.py",
		Language: "python",
		Content:  djangoSource,
	}
	r := d.Detect(ctx)
	if r == nil || len(r.Nodes) != 2 {
		t.Fatalf("expected 2 ENTITY nodes, got %d: %+v", len(r.Nodes), r.Nodes)
	}
	want := map[string]bool{"Author": false, "Book": false}
	for _, n := range r.Nodes {
		if _, ok := want[n.Label]; !ok {
			t.Errorf("unexpected label %q", n.Label)
		} else {
			want[n.Label] = true
		}
		if n.Kind != model.NodeEntity {
			t.Errorf("kind = %v, want NodeEntity", n.Kind)
		}
		if n.Properties["framework"] != "django" {
			t.Errorf("framework = %v", n.Properties["framework"])
		}
		if n.Source != "DjangoModelDetector" {
			t.Errorf("source = %q", n.Source)
		}
	}
	for lbl, found := range want {
		if !found {
			t.Errorf("missing entity %q", lbl)
		}
	}
	// Expect a ForeignKey edge from Book -> Author.
	if len(r.Edges) != 1 {
		t.Fatalf("expected 1 edge, got %d: %+v", len(r.Edges), r.Edges)
	}
	if r.Edges[0].Kind != model.EdgeDependsOn {
		t.Errorf("edge kind = %v, want DEPENDS_ON", r.Edges[0].Kind)
	}
}

func TestDjangoModelNegative(t *testing.T) {
	d := NewDjangoModelDetector()
	ctx := &detector.Context{
		FilePath: "app/util.py",
		Language: "python",
		Content:  "def hi():\n    return 1\n",
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestDjangoModelDeterminism(t *testing.T) {
	d := NewDjangoModelDetector()
	ctx := &detector.Context{
		FilePath: "app/models.py",
		Language: "python",
		Content:  djangoSource,
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic node id at %d", i)
		}
	}
}
