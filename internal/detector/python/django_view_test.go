package python

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/model"
)

const djangoViewSource = `from django.urls import path
from django.views.generic import ListView, DetailView

urlpatterns = [
    path('users/', UserListView.as_view(), name='user_list'),
    path('users/<int:pk>/', UserDetailView.as_view(), name='user_detail'),
]

class UserListView(ListView):
    model = User

class UserDetailView(DetailView, LoginRequiredMixin):
    model = User
`

func TestDjangoViewPositive(t *testing.T) {
	d := NewDjangoViewDetector()
	ctx := &detector.Context{
		FilePath: "app/views.py",
		Language: "python",
		Content:  djangoViewSource,
	}
	r := d.Detect(ctx)
	var endpoints, classes int
	for _, n := range r.Nodes {
		switch n.Kind {
		case model.NodeEndpoint:
			endpoints++
		case model.NodeClass:
			classes++
		}
	}
	if endpoints != 2 {
		t.Errorf("expected 2 endpoints, got %d", endpoints)
	}
	if classes != 2 {
		t.Errorf("expected 2 CBV classes, got %d", classes)
	}
}

func TestDjangoViewNegative(t *testing.T) {
	d := NewDjangoViewDetector()
	if len(d.Detect(&detector.Context{FilePath: "x.py", Content: "x = 1"}).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestDjangoViewDeterminism(t *testing.T) {
	d := NewDjangoViewDetector()
	ctx := &detector.Context{FilePath: "app/views.py", Language: "python", Content: djangoViewSource}
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
