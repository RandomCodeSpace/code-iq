package csharp

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const efcoreSource = `using Microsoft.EntityFrameworkCore;

public class AppDbContext : DbContext {
    public DbSet<User> Users { get; set; }
    public DbSet<Order> Orders { get; set; }
}

public class AddUserTable : Migration {
    protected override void Up(MigrationBuilder b) {
        b.CreateTable(name: "users");
        b.CreateTable("audit");
    }
}
`

func TestCSharpEfcorePositive(t *testing.T) {
	d := NewEfcoreDetector()
	r := d.Detect(&detector.Context{FilePath: "Db.cs", Language: "csharp", Content: efcoreSource})
	if r == nil {
		t.Fatal("nil result")
	}

	kinds := map[model.NodeKind]int{}
	for _, n := range r.Nodes {
		kinds[n.Kind]++
	}
	if kinds[model.NodeRepository] != 1 {
		t.Errorf("expected 1 REPOSITORY, got %d", kinds[model.NodeRepository])
	}
	// Entities: User, Order from DbSet + audit from CreateTable (users already exists by name from CreateTable but no — DbSet creates "User"/"Order" entities; CreateTable creates "users", "audit")
	if kinds[model.NodeEntity] < 3 {
		t.Errorf("expected >=3 ENTITY, got %d", kinds[model.NodeEntity])
	}
	if kinds[model.NodeMigration] != 1 {
		t.Errorf("expected 1 MIGRATION, got %d", kinds[model.NodeMigration])
	}

	queryEdges := 0
	for _, e := range r.Edges {
		if e.Kind == model.EdgeQueries {
			queryEdges++
		}
	}
	// 2 DbSet * 1 context = 2 query edges
	if queryEdges != 2 {
		t.Errorf("expected 2 QUERIES edges, got %d", queryEdges)
	}
}

func TestCSharpEfcoreNegative(t *testing.T) {
	d := NewEfcoreDetector()
	r := d.Detect(&detector.Context{FilePath: "x.cs", Language: "csharp", Content: "public class Foo {}"})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestCSharpEfcoreDeterminism(t *testing.T) {
	d := NewEfcoreDetector()
	ctx := &detector.Context{FilePath: "Db.cs", Language: "csharp", Content: efcoreSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
