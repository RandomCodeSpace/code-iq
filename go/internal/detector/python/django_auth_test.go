package python

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const djangoAuthSource = `from django.contrib.auth.decorators import login_required, permission_required, user_passes_test
from django.contrib.auth.mixins import LoginRequiredMixin, PermissionRequiredMixin
from django.views.generic import ListView

@login_required
def my_view(request):
    pass

@permission_required('app.change_thing')
def edit(request):
    pass

@user_passes_test(is_staff)
def staff_only(request):
    pass

class Dashboard(LoginRequiredMixin, ListView):
    model = Item
`

func TestDjangoAuthPositive(t *testing.T) {
	d := NewDjangoAuthDetector()
	ctx := &detector.Context{
		FilePath: "app/views.py",
		Language: "python",
		Content:  djangoAuthSource,
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 4 {
		t.Fatalf("expected 4 guard nodes (3 decorators + 1 mixin), got %d", len(r.Nodes))
	}
	for _, n := range r.Nodes {
		if n.Kind != model.NodeGuard {
			t.Errorf("kind = %v", n.Kind)
		}
		if n.Properties["auth_type"] != "django" {
			t.Errorf("auth_type = %v", n.Properties["auth_type"])
		}
	}
}

func TestDjangoAuthNegative(t *testing.T) {
	d := NewDjangoAuthDetector()
	if len(d.Detect(&detector.Context{FilePath: "x.py", Content: "x = 1"}).Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestDjangoAuthDeterminism(t *testing.T) {
	d := NewDjangoAuthDetector()
	ctx := &detector.Context{FilePath: "app/v.py", Language: "python", Content: djangoAuthSource}
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
