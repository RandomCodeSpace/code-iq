package python

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const flaskSource = `from flask import Flask, Blueprint

app = Flask(__name__)
bp = Blueprint("api", __name__)

@app.route("/", methods=["GET"])
def home():
    return "hi"

@app.route("/users", methods=["POST", "GET"])
def users():
    return "users"

@bp.route("/items")
def items():
    return "items"
`

func TestFlaskRoutePositive(t *testing.T) {
	d := NewFlaskRouteDetector()
	ctx := &detector.Context{
		FilePath: "app/server.py",
		Language: "python",
		Content:  flaskSource,
	}
	r := d.Detect(ctx)
	if r == nil || len(r.Nodes) != 3 {
		t.Fatalf("expected 3 ENDPOINT nodes, got %d: %+v", len(r.Nodes), r.Nodes)
	}
	sort.Slice(r.Nodes, func(i, j int) bool { return r.Nodes[i].Label < r.Nodes[j].Label })
	wantLabels := []string{"home", "items", "users"}
	for i, n := range r.Nodes {
		if n.Label != wantLabels[i] {
			t.Errorf("Label[%d] = %q, want %q", i, n.Label, wantLabels[i])
		}
		if n.Kind != model.NodeEndpoint {
			t.Errorf("kind = %v", n.Kind)
		}
		if n.Properties["framework"] != "flask" {
			t.Errorf("framework = %v", n.Properties["framework"])
		}
		if n.Source != "FlaskRouteDetector" {
			t.Errorf("source = %q", n.Source)
		}
	}
}

func TestFlaskRouteNegative(t *testing.T) {
	d := NewFlaskRouteDetector()
	ctx := &detector.Context{
		FilePath: "x.py",
		Language: "python",
		Content:  "def foo():\n    pass\n",
	}
	if len(d.Detect(ctx).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestFlaskRouteDeterminism(t *testing.T) {
	d := NewFlaskRouteDetector()
	ctx := &detector.Context{
		FilePath: "app/server.py",
		Language: "python",
		Content:  flaskSource,
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic count")
	}
	sort.Slice(r1.Nodes, func(i, j int) bool { return r1.Nodes[i].ID < r1.Nodes[j].ID })
	sort.Slice(r2.Nodes, func(i, j int) bool { return r2.Nodes[i].ID < r2.Nodes[j].ID })
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("non-deterministic id at %d", i)
		}
	}
}
