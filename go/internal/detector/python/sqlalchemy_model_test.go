package python

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const sqlaSource = `from sqlalchemy import Column, Integer, String
from sqlalchemy.orm import declarative_base, relationship

Base = declarative_base()

class User(Base):
    __tablename__ = 'users'

    id = Column(Integer, primary_key=True)
    name = Column(String(50))
    posts = relationship("Post", back_populates="author")

class Post(Base):
    __tablename__ = 'posts'

    id = Column(Integer, primary_key=True)
    title = Column(String(100))
    author = relationship("User", back_populates="posts")
`

func TestSQLAlchemyPositive(t *testing.T) {
	d := NewSQLAlchemyModelDetector()
	ctx := &detector.Context{
		FilePath: "app/models.py",
		Language: "python",
		Content:  sqlaSource,
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 2 {
		t.Fatalf("expected 2 entities, got %d", len(r.Nodes))
	}
	for _, n := range r.Nodes {
		if n.Kind != model.NodeEntity {
			t.Errorf("kind = %v", n.Kind)
		}
		if n.Properties["framework"] != "sqlalchemy" {
			t.Errorf("framework = %v", n.Properties["framework"])
		}
		cols := n.Properties["columns"].([]string)
		if len(cols) < 2 {
			t.Errorf("expected at least 2 columns on %s, got %v", n.Label, cols)
		}
	}
	if len(r.Edges) != 2 {
		t.Errorf("expected 2 MAPS_TO edges, got %d", len(r.Edges))
	}
}

func TestSQLAlchemyNegative(t *testing.T) {
	d := NewSQLAlchemyModelDetector()
	if len(d.Detect(&detector.Context{FilePath: "x.py", Content: "x = 1"}).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestSQLAlchemyDeterminism(t *testing.T) {
	d := NewSQLAlchemyModelDetector()
	ctx := &detector.Context{FilePath: "app/m.py", Language: "python", Content: sqlaSource}
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
