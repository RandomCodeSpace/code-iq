package python

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const fastapiAuthSource = `from fastapi import Depends, Security
from fastapi.security import HTTPBearer, HTTPBasic, OAuth2PasswordBearer

bearer = HTTPBearer()
basic = HTTPBasic()
oauth2 = OAuth2PasswordBearer(tokenUrl="/token")

def get_current_user(token=Depends(oauth2)):
    return token

@app.get("/items")
def items(user=Depends(get_current_user)):
    return []

@app.get("/admin")
def admin(scopes=Security(check_scope)):
    return []
`

func TestFastAPIAuthPositive(t *testing.T) {
	d := NewFastAPIAuthDetector()
	ctx := &detector.Context{
		FilePath: "app/main.py",
		Language: "python",
		Content:  fastapiAuthSource,
	}
	r := d.Detect(ctx)
	if len(r.Nodes) < 4 {
		t.Errorf("expected 4+ guards, got %d", len(r.Nodes))
	}
	for _, n := range r.Nodes {
		if n.Kind != model.NodeGuard {
			t.Errorf("kind = %v", n.Kind)
		}
		if n.Properties["auth_type"] != "fastapi" {
			t.Errorf("auth_type wrong")
		}
	}
}

func TestFastAPIAuthNegative(t *testing.T) {
	d := NewFastAPIAuthDetector()
	if len(d.Detect(&detector.Context{FilePath: "x.py", Content: "x = 1"}).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestFastAPIAuthDeterminism(t *testing.T) {
	d := NewFastAPIAuthDetector()
	ctx := &detector.Context{FilePath: "app/main.py", Language: "python", Content: fastapiAuthSource}
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
