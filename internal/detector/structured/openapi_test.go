package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

func TestOpenApiDetector_OpenAPI3(t *testing.T) {
	d := NewOpenApiDetector()
	ctx := &detector.Context{
		FilePath: "api.json",
		Language: "json",
		ParsedData: map[string]any{
			"type": "json",
			"data": map[string]any{
				"openapi": "3.0.0",
				"info":    map[string]any{"title": "Pet Store", "version": "1.0"},
				"paths": map[string]any{
					"/pets": map[string]any{
						"get":  map[string]any{"summary": "List pets", "operationId": "listPets"},
						"post": map[string]any{"summary": "Create pet"},
					},
				},
				"components": map[string]any{"schemas": map[string]any{
					"Pet":   map[string]any{"type": "object"},
					"Error": map[string]any{"type": "object"},
				}},
			},
		},
	}
	r := d.Detect(ctx)
	// 1 config_file + 2 endpoints + 2 schemas
	if len(r.Nodes) != 5 {
		t.Fatalf("expected 5 nodes, got %d", len(r.Nodes))
	}
	var sawEndpoint, sawEntity bool
	for _, n := range r.Nodes {
		if n.Kind == model.NodeEndpoint {
			sawEndpoint = true
		}
		if n.Kind == model.NodeEntity {
			sawEntity = true
		}
	}
	if !sawEndpoint || !sawEntity {
		t.Errorf("missing kinds: endpoint=%v entity=%v", sawEndpoint, sawEntity)
	}
}

func TestOpenApiDetector_SchemaRefs(t *testing.T) {
	d := NewOpenApiDetector()
	ctx := &detector.Context{
		FilePath: "api.yaml",
		Language: "yaml",
		ParsedData: map[string]any{
			"type": "yaml",
			"data": map[string]any{
				"openapi": "3.0.0",
				"info":    map[string]any{"title": "API", "version": "1.0"},
				"paths":   map[string]any{},
				"components": map[string]any{"schemas": map[string]any{
					"Order": map[string]any{
						"type":       "object",
						"properties": map[string]any{"customer": map[string]any{"$ref": "#/components/schemas/Customer"}},
					},
					"Customer": map[string]any{"type": "object"},
				}},
			},
		},
	}
	r := d.Detect(ctx)
	var sawDep bool
	for _, e := range r.Edges {
		if e.Kind == model.EdgeDependsOn {
			sawDep = true
		}
	}
	if !sawDep {
		t.Fatal("missing DEPENDS_ON edge from $ref")
	}
}

func TestOpenApiDetector_NotOpenApi(t *testing.T) {
	d := NewOpenApiDetector()
	ctx := &detector.Context{
		FilePath: "config.json",
		Language: "json",
		ParsedData: map[string]any{
			"type": "json",
			"data": map[string]any{"name": "not-openapi"},
		},
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestOpenApiDetector_Deterministic(t *testing.T) {
	d := NewOpenApiDetector()
	ctx := &detector.Context{
		FilePath: "api.json",
		Language: "json",
		ParsedData: map[string]any{
			"type": "json",
			"data": map[string]any{
				"openapi": "3.0.0",
				"info":    map[string]any{"title": "API", "version": "1.0"},
				"paths":   map[string]any{"/health": map[string]any{"get": map[string]any{}}},
			},
		},
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
