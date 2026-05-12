package python

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const fastapiSource = `from fastapi import FastAPI, APIRouter

app = FastAPI()
router = APIRouter(prefix="/api/v1")

@app.get("/")
async def index():
    return {"hello": "world"}

@router.post("/users")
def create_user(user: User):
    return user

@router.delete("/users/{id}")
async def delete_user(id: int):
    return {"deleted": id}
`

func TestFastAPIRoutePositive(t *testing.T) {
	d := NewFastAPIRouteDetector()
	ctx := &detector.Context{
		FilePath: "app/main.py",
		Language: "python",
		Content:  fastapiSource,
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 3 {
		t.Fatalf("expected 3 routes, got %d", len(r.Nodes))
	}
	// Verify prefix applied to router routes
	var sawPrefixed bool
	for _, n := range r.Nodes {
		path := n.Properties["path_pattern"].(string)
		if path == "/api/v1/users" || path == "/api/v1/users/{id}" {
			sawPrefixed = true
		}
		if n.Kind != model.NodeEndpoint {
			t.Errorf("kind = %v", n.Kind)
		}
	}
	if !sawPrefixed {
		t.Error("router prefix not applied")
	}
}

func TestFastAPIRouteNegative(t *testing.T) {
	d := NewFastAPIRouteDetector()
	if len(d.Detect(&detector.Context{FilePath: "x.py", Content: "x = 1"}).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestFastAPIRouteDeterminism(t *testing.T) {
	d := NewFastAPIRouteDetector()
	ctx := &detector.Context{FilePath: "app/main.py", Language: "python", Content: fastapiSource}
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
