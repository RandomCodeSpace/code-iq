package typescript

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const nestjsGuardsSource = `import { Controller, Get, UseGuards } from '@nestjs/common';
import { Roles } from '@nestjs/passport';

@Controller('users')
@UseGuards(JwtAuthGuard, RolesGuard)
export class UsersController {

    @Get('admin')
    @Roles('admin', 'super-admin')
    async admin() {}

    @Get()
    @UseGuards(AuthGuard('jwt'))
    list() {}
}

class CustomGuard {
    canActivate(ctx) { return true; }
}
`

func TestNestJSGuardsPositive(t *testing.T) {
	d := NewNestJSGuardsDetector()
	ctx := &detector.Context{
		FilePath: "src/users.controller.ts",
		Language: "typescript",
		Content:  nestjsGuardsSource,
	}
	r := d.Detect(ctx)
	var guardCount int
	for _, n := range r.Nodes {
		if n.Kind != model.NodeGuard {
			t.Errorf("unexpected kind: %v", n.Kind)
		}
		guardCount++
	}
	if guardCount < 4 {
		t.Errorf("expected at least 4 guard nodes, got %d", guardCount)
	}
	// Check Roles node has roles list
	rolesNodes := 0
	for _, n := range r.Nodes {
		if rs, ok := n.Properties["roles"].([]string); ok && len(rs) == 2 {
			rolesNodes++
		}
	}
	if rolesNodes < 1 {
		t.Errorf("expected at least 1 node with 2 roles, got %d", rolesNodes)
	}
}

func TestNestJSGuardsGuardRejects(t *testing.T) {
	d := NewNestJSGuardsDetector()
	src := `@UseGuards(Anything) class X {}`
	ctx := &detector.Context{
		FilePath: "src/x.ts",
		Language: "typescript",
		Content:  src,
	}
	if len(d.Detect(ctx).Nodes) != 0 {
		t.Fatal("guard should reject without @nestjs/ import")
	}
}

func TestNestJSGuardsDeterminism(t *testing.T) {
	d := NewNestJSGuardsDetector()
	ctx := &detector.Context{
		FilePath: "src/x.controller.ts",
		Language: "typescript",
		Content:  nestjsGuardsSource,
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
