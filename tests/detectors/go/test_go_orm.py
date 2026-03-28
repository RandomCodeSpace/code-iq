"""Tests for Go ORM/database detector."""

from code_intelligence.detectors.base import DetectorContext, DetectorResult
from code_intelligence.detectors.go.go_orm import GoOrmDetector
from code_intelligence.models.graph import EdgeKind, NodeKind


def _ctx(content: str, path: str = "models.go") -> DetectorContext:
    return DetectorContext(
        file_path=path, language="go", content=content.encode(), module_name="test"
    )


class TestGoOrmDetector:
    def setup_method(self):
        self.detector = GoOrmDetector()

    def test_name_and_languages(self):
        assert self.detector.name == "go_orm"
        assert self.detector.supported_languages == ("go",)

    # --- GORM ---

    def test_detects_gorm_entity(self):
        source = """\
package models

import "gorm.io/gorm"

type User struct {
    gorm.Model
    Name  string
    Email string
}

type Product struct {
    gorm.Model
    Title string
    Price float64
}
"""
        result = self.detector.detect(_ctx(source))
        entities = [n for n in result.nodes if n.kind == NodeKind.ENTITY]
        assert len(entities) == 2
        labels = {n.label for n in entities}
        assert labels == {"User", "Product"}
        for e in entities:
            assert e.properties["framework"] == "gorm"

    def test_detects_gorm_migration(self):
        source = """\
package main

import "gorm.io/gorm"

func main() {
    db.AutoMigrate(&User{}, &Product{})
}
"""
        result = self.detector.detect(_ctx(source))
        migrations = [n for n in result.nodes if n.kind == NodeKind.MIGRATION]
        assert len(migrations) == 1
        assert migrations[0].properties["framework"] == "gorm"
        assert migrations[0].properties["type"] == "auto_migrate"

    def test_detects_gorm_queries(self):
        source = """\
package handlers

import "gorm.io/gorm"

func GetUsers(db *gorm.DB) {
    db.Find(&users)
    db.Where("name = ?", name).First(&user)
    db.Create(&newUser)
    db.Save(&user)
    db.Delete(&user)
}
"""
        result = self.detector.detect(_ctx(source))
        query_edges = [e for e in result.edges if e.kind == EdgeKind.QUERIES]
        assert len(query_edges) == 6  # Where and First on same line count separately
        ops = {e.properties["operation"] for e in query_edges}
        assert ops == {"Find", "Where", "First", "Create", "Save", "Delete"}
        for edge in query_edges:
            assert edge.properties["framework"] == "gorm"

    # --- sqlx ---

    def test_detects_sqlx_connection(self):
        source = """\
package db

import "github.com/jmoiron/sqlx"

func Init() {
    db := sqlx.Connect("postgres", connStr)
    db2 := sqlx.Open("mysql", connStr)
}
"""
        result = self.detector.detect(_ctx(source))
        connections = [n for n in result.nodes if n.kind == NodeKind.DATABASE_CONNECTION]
        assert len(connections) == 2
        for conn in connections:
            assert conn.properties["framework"] == "sqlx"

    def test_detects_sqlx_queries(self):
        source = """\
package repo

import "github.com/jmoiron/sqlx"

func GetUser(db *sqlx.DB) {
    db.Select(&users, "SELECT * FROM users")
    db.Get(&user, "SELECT * FROM users WHERE id=$1", id)
    db.NamedExec("INSERT INTO users (name) VALUES (:name)", user)
}
"""
        result = self.detector.detect(_ctx(source))
        query_edges = [e for e in result.edges if e.kind == EdgeKind.QUERIES]
        assert len(query_edges) == 3
        ops = {e.properties["operation"] for e in query_edges}
        assert ops == {"Select", "Get", "NamedExec"}
        for edge in query_edges:
            assert edge.properties["framework"] == "sqlx"

    # --- database/sql ---

    def test_detects_sql_open(self):
        source = """\
package main

import "database/sql"

func main() {
    db, err := sql.Open("postgres", connStr)
}
"""
        result = self.detector.detect(_ctx(source))
        connections = [n for n in result.nodes if n.kind == NodeKind.DATABASE_CONNECTION]
        assert len(connections) == 1
        assert connections[0].properties["framework"] == "database_sql"

    def test_detects_sql_queries(self):
        source = """\
package repo

import "database/sql"

func GetData(db *sql.DB) {
    db.Query("SELECT * FROM users")
    db.QueryRow("SELECT * FROM users WHERE id=$1", id)
    db.Exec("DELETE FROM users WHERE id=$1", id)
}
"""
        result = self.detector.detect(_ctx(source))
        query_edges = [e for e in result.edges if e.kind == EdgeKind.QUERIES]
        assert len(query_edges) == 3
        ops = {e.properties["operation"] for e in query_edges}
        assert ops == {"Query", "QueryRow", "Exec"}
        for edge in query_edges:
            assert edge.properties["framework"] == "database_sql"

    # --- Negative ---

    def test_empty_returns_nothing(self):
        result = self.detector.detect(_ctx("package main\n\nfunc main() {}\n"))
        assert len(result.nodes) == 0
        assert len(result.edges) == 0

    def test_no_orm_patterns(self):
        source = """\
package main

import "fmt"

func main() {
    fmt.Println("no database here")
}
"""
        result = self.detector.detect(_ctx(source))
        assert len(result.nodes) == 0
        assert len(result.edges) == 0

    # --- Determinism ---

    def test_determinism(self):
        source = """\
package models

import "gorm.io/gorm"

type Account struct {
    gorm.Model
    Balance float64
}

type Order struct {
    gorm.Model
    Total float64
}
"""
        ctx = _ctx(source)
        r1 = self.detector.detect(ctx)
        r2 = self.detector.detect(ctx)
        assert len(r1.nodes) == len(r2.nodes)
        assert [n.id for n in r1.nodes] == [n.id for n in r2.nodes]
        assert len(r1.edges) == len(r2.edges)

    def test_returns_detector_result(self):
        result = self.detector.detect(_ctx("package main\n"))
        assert isinstance(result, DetectorResult)

    def test_entity_node_id_format(self):
        source = """\
type User struct {
    gorm.Model
    Name string
}
"""
        result = self.detector.detect(_ctx(source, path="user.go"))
        entities = [n for n in result.nodes if n.kind == NodeKind.ENTITY]
        assert len(entities) == 1
        assert entities[0].id.startswith("go_orm:user.go:")
