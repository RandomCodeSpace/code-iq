package sql

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestSqlMigration_Flyway(t *testing.T) {
	d := NewSqlMigrationDetector()
	r := d.Detect(&detector.Context{
		FilePath: "db/migration/V1_2__add_users.sql",
		Language: "sql",
		Content: `CREATE TABLE users (id INT PRIMARY KEY, email TEXT);
CREATE INDEX idx_users_email ON users(email);
ALTER TABLE users ADD COLUMN created_at TIMESTAMP;`,
	})
	if len(r.Nodes) < 2 {
		t.Fatalf("expected >=2 nodes (1 sql_entity + 1 migration), got %d", len(r.Nodes))
	}
	hasMig := false
	hasEntity := false
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeMigration:
			hasMig = true
			if got, _ := n.Properties["format"].(string); got != "flyway" {
				t.Errorf("format = %v want flyway", n.Properties["format"])
			}
			if got, _ := n.Properties["version"].(string); got != "1.2" {
				t.Errorf("version = %v want 1.2", n.Properties["version"])
			}
		case model.NodeSQLEntity:
			hasEntity = true
		}
	}
	if !hasMig {
		t.Fatalf("missing MIGRATION node")
	}
	if !hasEntity {
		t.Fatalf("missing SQL_ENTITY node")
	}
}

func TestSqlMigration_BareSql(t *testing.T) {
	d := NewSqlMigrationDetector()
	r := d.Detect(&detector.Context{
		FilePath: "schema.sql",
		Language: "sql",
		Content:  "CREATE TABLE orders (id INT);\nCREATE VIEW v_orders AS SELECT * FROM orders;",
	})
	if len(r.Nodes) < 2 {
		t.Fatalf("expected >=2 sql_entities, got %d", len(r.Nodes))
	}
}

func TestSqlMigration_NotMigration(t *testing.T) {
	d := NewSqlMigrationDetector()
	// Plain Python file: no Alembic marker -> nothing detected.
	r := d.Detect(&detector.Context{FilePath: "app.py", Language: "python", Content: "x = 1"})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0, got %d", len(r.Nodes))
	}
}

func TestSqlMigration_Alembic(t *testing.T) {
	d := NewSqlMigrationDetector()
	r := d.Detect(&detector.Context{
		FilePath: "alembic/versions/abc_init.py",
		Language: "python",
		Content: `from alembic import op
import sqlalchemy as sa

def upgrade():
    op.create_table('users')
    op.add_column('users', sa.Column('email', sa.String))`,
	})
	if len(r.Nodes) < 2 {
		t.Fatalf("expected alembic nodes, got %d", len(r.Nodes))
	}
}

func TestSqlMigration_Determinism(t *testing.T) {
	d := NewSqlMigrationDetector()
	c := &detector.Context{
		FilePath: "db/migration/V1__init.sql",
		Language: "sql",
		Content:  "CREATE TABLE a (x INT); CREATE TABLE b (y INT);",
	}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic node count")
	}
	for i := range r1.Nodes {
		if r1.Nodes[i].ID != r2.Nodes[i].ID {
			t.Fatalf("node[%d] id drift: %q vs %q", i, r1.Nodes[i].ID, r2.Nodes[i].ID)
		}
	}
}
