package frontend

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestFrontendRoute_React(t *testing.T) {
	d := NewFrontendRouteDetector()
	src := `<Routes>
  <Route path="/home" component={Home}/>
  <Route path="/about" element={<About />}/>
  <Route path="/contact"/>
</Routes>`
	r := d.Detect(&detector.Context{FilePath: "src/App.tsx", Language: "typescript", Content: src})
	if len(r.Nodes) != 3 {
		t.Fatalf("expected 3 routes, got %d", len(r.Nodes))
	}
	for _, n := range r.Nodes {
		if n.Properties["framework"] != "react" {
			t.Errorf("framework = %v want react", n.Properties["framework"])
		}
		if n.Kind != model.NodeEndpoint {
			t.Errorf("kind = %v", n.Kind)
		}
	}
	if len(r.Edges) != 2 {
		t.Fatalf("expected 2 renders edges, got %d", len(r.Edges))
	}
}

func TestFrontendRoute_NextjsPages(t *testing.T) {
	d := NewFrontendRouteDetector()
	r := d.Detect(&detector.Context{FilePath: "pages/about.tsx", Language: "typescript", Content: "export default function About(){return null}"})
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 node, got %d", len(r.Nodes))
	}
	if r.Nodes[0].Properties["framework"] != "nextjs" {
		t.Errorf("framework = %v", r.Nodes[0].Properties["framework"])
	}
	if r.Nodes[0].Properties["route_path"] != "/about" {
		t.Errorf("route_path = %v", r.Nodes[0].Properties["route_path"])
	}
}

func TestFrontendRoute_NextjsApp(t *testing.T) {
	d := NewFrontendRouteDetector()
	r := d.Detect(&detector.Context{FilePath: "app/blog/[slug]/page.tsx", Language: "typescript", Content: "export default function P(){return null}"})
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 node, got %d", len(r.Nodes))
	}
	if r.Nodes[0].Properties["route_path"] != "/blog/[slug]" {
		t.Errorf("route_path = %v", r.Nodes[0].Properties["route_path"])
	}
}

func TestFrontendRoute_Vue(t *testing.T) {
	d := NewFrontendRouteDetector()
	src := `const router = createRouter({
  routes: [
    { path: '/home', component: Home },
    { path: '/about', component: About },
  ]
})`
	r := d.Detect(&detector.Context{FilePath: "src/router.ts", Language: "typescript", Content: src})
	if len(r.Nodes) < 2 {
		t.Fatalf("expected >=2 routes, got %d", len(r.Nodes))
	}
}

func TestFrontendRoute_Determinism(t *testing.T) {
	d := NewFrontendRouteDetector()
	src := `<Route path="/x" component={X}/>`
	c := &detector.Context{FilePath: "src/App.tsx", Language: "typescript", Content: src}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}
