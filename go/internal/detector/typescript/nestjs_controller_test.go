package typescript

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const nestjsControllerSource = `import { Controller, Get, Post } from '@nestjs/common';

@Controller('users')
export class UsersController {
    @Get(':id')
    async findOne(@Param('id') id: string) {
        return { id };
    }

    @Post()
    create(@Body() data: any) {
        return data;
    }
}
`

func TestNestJSControllerPositive(t *testing.T) {
	d := NewNestJSControllerDetector()
	ctx := &detector.Context{
		FilePath:   "src/users.controller.ts",
		Language:   "typescript",
		Content:    nestjsControllerSource,
		ModuleName: "users",
	}
	r := d.Detect(ctx)
	if r == nil {
		t.Fatal("nil result")
	}
	var classes, endpoints int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeClass:
			classes++
		case model.NodeEndpoint:
			endpoints++
		}
	}
	if classes != 1 {
		t.Errorf("expected 1 class node, got %d", classes)
	}
	if endpoints != 2 {
		t.Errorf("expected 2 endpoint nodes, got %d", endpoints)
	}
	if len(r.Edges) != 2 {
		t.Errorf("expected 2 EXPOSES edges, got %d", len(r.Edges))
	}
	for _, e := range r.Edges {
		if e.Kind != model.EdgeExposes {
			t.Errorf("edge kind = %v", e.Kind)
		}
	}
}

func TestNestJSControllerGuardRejects(t *testing.T) {
	// Express-style routing without @nestjs/* import must NOT match.
	d := NewNestJSControllerDetector()
	src := `@Controller('x')
export class X {
    @Get('/y')
    handler() {}
}`
	ctx := &detector.Context{
		FilePath: "src/x.ts",
		Language: "typescript",
		Content:  src,
	}
	if len(d.Detect(ctx).Nodes) != 0 {
		t.Fatal("guard should reject files without @nestjs import")
	}
}

func TestNestJSControllerDeterminism(t *testing.T) {
	d := NewNestJSControllerDetector()
	ctx := &detector.Context{
		FilePath:   "src/x.controller.ts",
		Language:   "typescript",
		Content:    nestjsControllerSource,
		ModuleName: "users",
	}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
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
