package golang

import (
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const goOrmGorm = `package db

import "gorm.io/gorm"

type User struct {
	gorm.Model
	Name string
}

func setup(db *gorm.DB) {
	db.AutoMigrate(&User{})
	db.Create(&User{Name: "x"})
	db.Find(&User{})
	db.Where("name = ?", "x").First(&User{})
	db.Save(&User{})
	db.Delete(&User{})
}
`

const goOrmSqlx = `package db

import "github.com/jmoiron/sqlx"

func setup() {
	db, _ := sqlx.Connect("postgres", "")
	db.Select(&users, "select 1")
	db.Get(&user, "select 1")
}
`

const goOrmSql = `package db

import "database/sql"

func setup() {
	db, _ := sql.Open("postgres", "")
	db.Query("select 1")
	db.Exec("insert into x values(1)")
}
`

func TestGoOrmGormEntity(t *testing.T) {
	d := NewOrmDetector()
	r := d.Detect(&detector.Context{FilePath: "db/db.go", Language: "go", Content: goOrmGorm})
	if r == nil {
		t.Fatal("nil result")
	}
	var entity *model.CodeNode
	for _, n := range r.Nodes {
		if n.Kind == model.NodeEntity && n.Label == "User" {
			entity = n
		}
	}
	if entity == nil {
		t.Fatal("expected User entity node")
	}
	if entity.Properties["framework"] != "gorm" {
		t.Errorf("framework = %v", entity.Properties["framework"])
	}

	migrationCount := 0
	for _, n := range r.Nodes {
		if n.Kind == model.NodeMigration {
			migrationCount++
		}
	}
	if migrationCount != 1 {
		t.Errorf("expected 1 migration, got %d", migrationCount)
	}

	if len(r.Edges) < 1 {
		t.Errorf("expected GORM query edges, got %d", len(r.Edges))
	}
	for _, e := range r.Edges {
		if e.Kind != model.EdgeQueries {
			t.Errorf("edge kind = %v", e.Kind)
		}
		if e.Properties["framework"] != "gorm" {
			t.Errorf("edge framework = %v", e.Properties["framework"])
		}
	}
}

func TestGoOrmSqlxConnection(t *testing.T) {
	d := NewOrmDetector()
	r := d.Detect(&detector.Context{FilePath: "db/db.go", Language: "go", Content: goOrmSqlx})
	connCount := 0
	for _, n := range r.Nodes {
		if n.Kind == model.NodeDatabaseConnection {
			connCount++
			if n.Properties["framework"] != "sqlx" {
				t.Errorf("framework = %v", n.Properties["framework"])
			}
		}
	}
	if connCount != 1 {
		t.Errorf("expected 1 sqlx connection, got %d", connCount)
	}
	if len(r.Edges) < 1 {
		t.Error("expected sqlx query edges")
	}
}

func TestGoOrmDatabaseSql(t *testing.T) {
	d := NewOrmDetector()
	r := d.Detect(&detector.Context{FilePath: "db/db.go", Language: "go", Content: goOrmSql})
	hasConn := false
	for _, n := range r.Nodes {
		if n.Kind == model.NodeDatabaseConnection && n.Properties["framework"] == "database_sql" {
			hasConn = true
		}
	}
	if !hasConn {
		t.Error("expected database_sql connection")
	}
	queryEdges := 0
	for _, e := range r.Edges {
		if e.Properties["framework"] == "database_sql" {
			queryEdges++
		}
	}
	if queryEdges < 1 {
		t.Error("expected database/sql query edges")
	}
}

func TestGoOrmNegative(t *testing.T) {
	d := NewOrmDetector()
	r := d.Detect(&detector.Context{
		FilePath: "x.go", Language: "go",
		Content: "package main\nfunc main() {}\n",
	})
	if len(r.Nodes) != 0 || len(r.Edges) != 0 {
		t.Fatal("expected empty result")
	}
}

func TestGoOrmDeterminism(t *testing.T) {
	d := NewOrmDetector()
	ctx := &detector.Context{FilePath: "db/db.go", Language: "go", Content: goOrmGorm}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
