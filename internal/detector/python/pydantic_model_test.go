package python

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const pydanticSource = `from pydantic import BaseModel, BaseSettings, field_validator

class User(BaseModel):
    id: int
    name: str
    email: str

    @field_validator("email")
    def must_be_email(cls, v):
        return v

class AppSettings(BaseSettings):
    database_url: str
    debug: bool = False

    class Config:
        env_file = ".env"
`

func TestPydanticModelPositive(t *testing.T) {
	d := NewPydanticModelDetector()
	ctx := &detector.Context{
		FilePath: "app/models.py",
		Language: "python",
		Content:  pydanticSource,
	}
	r := d.Detect(ctx)
	var entities, configs int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeEntity:
			entities++
		case model.NodeConfigDefinition:
			configs++
		}
	}
	if entities != 1 {
		t.Errorf("expected 1 entity (BaseModel), got %d", entities)
	}
	if configs != 1 {
		t.Errorf("expected 1 config_definition (BaseSettings), got %d", configs)
	}
	for _, n := range r.Nodes {
		if n.Properties["framework"] != "pydantic" {
			t.Errorf("framework wrong on %s", n.Label)
		}
	}
}

func TestPydanticModelNegative(t *testing.T) {
	d := NewPydanticModelDetector()
	if len(d.Detect(&detector.Context{FilePath: "x.py", Content: "x = 1"}).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestPydanticModelDeterminism(t *testing.T) {
	d := NewPydanticModelDetector()
	ctx := &detector.Context{FilePath: "app/m.py", Language: "python", Content: pydanticSource}
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
