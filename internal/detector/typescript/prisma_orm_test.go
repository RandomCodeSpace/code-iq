package typescript

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const prismaSource = `import { PrismaClient } from '@prisma/client';
const prisma = new PrismaClient();

async function getUsers() {
    return prisma.user.findMany({ where: { active: true } });
}

async function createUser(data) {
    return prisma.user.create({ data });
}

async function updatePost(id, body) {
    await prisma.$transaction(async () => {
        await prisma.post.update({ where: { id }, data: { body } });
    });
}
`

func TestPrismaORMPositive(t *testing.T) {
	d := NewPrismaORMDetector()
	ctx := &detector.Context{
		FilePath: "src/db.ts",
		Language: "typescript",
		Content:  prismaSource,
	}
	r := d.Detect(ctx)
	var conn, entities int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeDatabaseConnection:
			conn++
			if n.Properties["transaction"] != true {
				t.Errorf("expected transaction property")
			}
		case model.NodeEntity:
			entities++
		}
	}
	if conn != 1 {
		t.Errorf("expected 1 connection, got %d", conn)
	}
	if entities != 2 {
		t.Errorf("expected 2 entity nodes (user, post), got %d", entities)
	}
	if len(r.Edges) < 3 {
		t.Errorf("expected at least 3 edges, got %d", len(r.Edges))
	}
}

func TestPrismaORMNegative(t *testing.T) {
	d := NewPrismaORMDetector()
	ctx := &detector.Context{FilePath: "x.ts", Language: "typescript", Content: "const x = 1;"}
	r := d.Detect(ctx)
	if len(r.Nodes) != 0 || len(r.Edges) != 0 {
		t.Fatal("expected nothing")
	}
}

func TestPrismaORMDeterminism(t *testing.T) {
	d := NewPrismaORMDetector()
	ctx := &detector.Context{FilePath: "src/db.ts", Language: "typescript", Content: prismaSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
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
