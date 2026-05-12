package typescript

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const expressSource = "const express = require('express');\n" +
	"const app = express();\n" +
	"const router = express.Router();\n" +
	"\n" +
	"app.get('/users', (req, res) => res.json([]));\n" +
	"app.post(\"/users\", (req, res) => res.json({}));\n" +
	"router.delete(`/users/:id`, (req, res) => res.sendStatus(204));\n"

func TestExpressRoutePositive(t *testing.T) {
	d := NewExpressRouteDetector()
	ctx := &detector.Context{
		FilePath: "src/routes.ts",
		Language: "typescript",
		Content:  expressSource,
	}
	r := d.Detect(ctx)
	if r == nil || len(r.Nodes) != 3 {
		t.Fatalf("expected 3 endpoints, got %d", len(r.Nodes))
	}
	sort.Slice(r.Nodes, func(i, j int) bool { return r.Nodes[i].Properties["http_method"].(string) < r.Nodes[j].Properties["http_method"].(string) })
	wantMethods := []string{"DELETE", "GET", "POST"}
	for i, n := range r.Nodes {
		if n.Kind != model.NodeEndpoint {
			t.Errorf("Kind[%d] = %v", i, n.Kind)
		}
		if n.Properties["http_method"] != wantMethods[i] {
			t.Errorf("method[%d] = %v want %s", i, n.Properties["http_method"], wantMethods[i])
		}
		if n.Properties["framework"] != "express" {
			t.Errorf("framework = %v", n.Properties["framework"])
		}
	}
}

func TestExpressRouteNegative(t *testing.T) {
	d := NewExpressRouteDetector()
	ctx := &detector.Context{
		FilePath: "src/no.ts",
		Language: "typescript",
		Content:  "const x = 1;\n",
	}
	if len(d.Detect(ctx).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestExpressRouteDeterminism(t *testing.T) {
	d := NewExpressRouteDetector()
	ctx := &detector.Context{
		FilePath: "src/routes.ts",
		Language: "typescript",
		Content:  expressSource,
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
			t.Fatalf("non-deterministic at %d", i)
		}
	}
}
