package typescript

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const typeormSource = `import { Entity, Column, PrimaryGeneratedColumn, ManyToOne } from 'typeorm';

@Entity('users')
export class User {
    @PrimaryGeneratedColumn()
    id: number;

    @Column()
    name: string;

    @Column({ nullable: true })
    email: string;

    @ManyToOne(() => Role)
    role: Role;
}
`

func TestTypeORMEntityPositive(t *testing.T) {
	d := NewTypeORMEntityDetector()
	ctx := &detector.Context{
		FilePath: "src/user.entity.ts",
		Language: "typescript",
		Content:  typeormSource,
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 entity, got %d", len(r.Nodes))
	}
	n := r.Nodes[0]
	if n.Properties["table_name"] != "users" {
		t.Errorf("table_name = %v", n.Properties["table_name"])
	}
	cols, ok := n.Properties["columns"].([]string)
	if !ok || len(cols) != 2 {
		t.Errorf("expected 2 columns, got %v", n.Properties["columns"])
	}
	if len(r.Edges) != 1 || r.Edges[0].Kind != model.EdgeMapsTo {
		t.Errorf("expected 1 MAPS_TO edge")
	}
}

func TestTypeORMEntityNegative(t *testing.T) {
	d := NewTypeORMEntityDetector()
	ctx := &detector.Context{FilePath: "x.ts", Language: "typescript", Content: "class A {}"}
	if len(d.Detect(ctx).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestTypeORMEntityDeterminism(t *testing.T) {
	d := NewTypeORMEntityDetector()
	ctx := &detector.Context{FilePath: "src/x.ts", Language: "typescript", Content: typeormSource}
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
