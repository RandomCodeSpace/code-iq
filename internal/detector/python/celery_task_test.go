package python

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const celerySource = `from celery import shared_task
from .tasks import app

@app.task(name='tasks.add')
def add(x, y):
    return x + y

@shared_task
def cleanup():
    pass

def main():
    add.delay(1, 2)
    cleanup.apply_async()
`

func TestCeleryTaskPositive(t *testing.T) {
	d := NewCeleryTaskDetector()
	ctx := &detector.Context{
		FilePath:   "app/tasks.py",
		Language:   "python",
		Content:    celerySource,
		ModuleName: "app.tasks",
	}
	r := d.Detect(ctx)
	var queues, methods int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeQueue:
			queues++
		case model.NodeMethod:
			methods++
		}
	}
	if queues != 2 {
		t.Errorf("expected 2 queue nodes, got %d", queues)
	}
	if methods != 2 {
		t.Errorf("expected 2 method nodes, got %d", methods)
	}
	var consumes, produces int
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeConsumes:
			consumes++
		case model.EdgeProduces:
			produces++
		}
	}
	if consumes != 2 {
		t.Errorf("expected 2 CONSUMES, got %d", consumes)
	}
	if produces != 2 {
		t.Errorf("expected 2 PRODUCES, got %d", produces)
	}
}

func TestCeleryTaskNegative(t *testing.T) {
	d := NewCeleryTaskDetector()
	if len(d.Detect(&detector.Context{FilePath: "x.py", Content: "x = 1"}).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestCeleryTaskDeterminism(t *testing.T) {
	d := NewCeleryTaskDetector()
	ctx := &detector.Context{FilePath: "app/tasks.py", Language: "python", Content: celerySource, ModuleName: "app.tasks"}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
	sort.Slice(r1.Nodes, func(i, j int) bool { return r1.Nodes[i].ID < r1.Nodes[j].ID })
	sort.Slice(r2.Nodes, func(i, j int) bool { return r2.Nodes[i].ID < r2.Nodes[j].ID })
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic at %d", i)
		}
	}
}
