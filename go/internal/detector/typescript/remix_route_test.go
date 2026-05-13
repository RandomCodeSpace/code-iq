package typescript

import (
	"sort"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const remixSource = `import { useLoaderData, useActionData } from '@remix-run/react';

export async function loader({ request }) {
    return { items: [] };
}

export async function action({ request }) {
    return { ok: true };
}

export default function UsersRoute() {
    const data = useLoaderData();
    const action = useActionData();
    return <div>{JSON.stringify(data)}</div>;
}
`

func TestRemixRoutePositive(t *testing.T) {
	d := NewRemixRouteDetector()
	ctx := &detector.Context{
		FilePath: "app/routes/users.tsx",
		Language: "typescript",
		Content:  remixSource,
	}
	r := d.Detect(ctx)
	var loaders, actions, components int
	for _, n := range r.Nodes {
		switch n.Properties["type"] {
		case "loader":
			loaders++
		case "action":
			actions++
		case "component":
			components++
			if n.Properties["uses_loader_data"] != true || n.Properties["uses_action_data"] != true {
				t.Errorf("expected uses_loader_data/uses_action_data flags")
			}
			if n.Kind != model.NodeComponent {
				t.Errorf("component kind = %v", n.Kind)
			}
		}
	}
	if loaders != 1 || actions != 1 || components != 1 {
		t.Errorf("expected 1/1/1 loader/action/component, got %d/%d/%d", loaders, actions, components)
	}
	for _, n := range r.Nodes {
		if rp, ok := n.Properties["route_path"]; !ok || rp != "/users" {
			t.Errorf("route_path = %v want /users", rp)
		}
	}
}

func TestRemixRouteIndex(t *testing.T) {
	d := NewRemixRouteDetector()
	ctx := &detector.Context{
		FilePath: "app/routes/_index.tsx",
		Language: "typescript",
		Content:  "export default function Index() { return <div />; }",
	}
	r := d.Detect(ctx)
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 component, got %d", len(r.Nodes))
	}
	if r.Nodes[0].Properties["route_path"] != "/" {
		t.Errorf("expected / for _index, got %v", r.Nodes[0].Properties["route_path"])
	}
}

func TestRemixRouteParam(t *testing.T) {
	d := NewRemixRouteDetector()
	ctx := &detector.Context{
		FilePath: "app/routes/users.$id.tsx",
		Language: "typescript",
		Content:  "export default function X() { return null; }",
	}
	r := d.Detect(ctx)
	if r.Nodes[0].Properties["route_path"] != "/users/:id" {
		t.Errorf("expected /users/:id, got %v", r.Nodes[0].Properties["route_path"])
	}
}

func TestRemixRouteDeterminism(t *testing.T) {
	d := NewRemixRouteDetector()
	ctx := &detector.Context{FilePath: "app/routes/x.tsx", Language: "typescript", Content: remixSource}
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
