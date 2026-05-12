package structured

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestSqlStructureDetector_TablesAndFKs(t *testing.T) {
	sql := `CREATE TABLE users (
    id INT PRIMARY KEY,
    name VARCHAR(100)
);

CREATE TABLE orders (
    id INT PRIMARY KEY,
    user_id INT REFERENCES users(id)
);

CREATE VIEW active_users AS SELECT * FROM users;

CREATE INDEX idx_user_name ON users(name);
`
	d := NewSqlStructureDetector()
	r := d.Detect(&detector.Context{FilePath: "schema.sql", Language: "sql", Content: sql})
	// 2 tables + 1 view + 1 index = 4 nodes
	if len(r.Nodes) != 4 {
		t.Fatalf("expected 4 nodes, got %d: %+v", len(r.Nodes), r.Nodes)
	}
	var sawEntity, sawCfgDef, sawFK bool
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeEntity:
			sawEntity = true
		case model.NodeConfigDefinition:
			sawCfgDef = true
		}
	}
	for _, e := range r.Edges {
		if e.Kind == model.EdgeDependsOn {
			sawFK = true
		}
	}
	if !sawEntity {
		t.Error("missing ENTITY node")
	}
	if !sawCfgDef {
		t.Error("missing CONFIG_DEFINITION node")
	}
	if !sawFK {
		t.Error("missing FK edge")
	}
}

func TestSqlStructureDetector_Procedure(t *testing.T) {
	sql := "CREATE OR REPLACE PROCEDURE update_stats\nAS BEGIN\nEND;"
	d := NewSqlStructureDetector()
	r := d.Detect(&detector.Context{FilePath: "procs.sql", Language: "sql", Content: sql})
	var sawProc bool
	for _, n := range r.Nodes {
		if n.Properties["entity_type"] == "procedure" {
			sawProc = true
		}
	}
	if !sawProc {
		t.Fatal("missing procedure entity_type")
	}
}

func TestSqlStructureDetector_Negative(t *testing.T) {
	d := NewSqlStructureDetector()
	r := d.Detect(&detector.Context{FilePath: "empty.sql", Language: "sql", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestSqlStructureDetector_Deterministic(t *testing.T) {
	sql := "CREATE TABLE t1 (id INT);\nCREATE TABLE t2 (id INT REFERENCES t1(id));"
	d := NewSqlStructureDetector()
	c := &detector.Context{FilePath: "schema.sql", Language: "sql", Content: sql}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatalf("non-deterministic counts")
	}
}
