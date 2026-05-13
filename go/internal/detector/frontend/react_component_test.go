package frontend

import (
	"strings"
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

func TestReactComponent_FunctionComponent(t *testing.T) {
	src := "export default function MyApp() {\n  return <div/>;\n}"
	d := NewReactComponentDetector()
	r := d.Detect(&detector.Context{FilePath: "App.tsx", Language: "typescript", Content: src})
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 node, got %d", len(r.Nodes))
	}
	if r.Nodes[0].Kind != model.NodeComponent {
		t.Errorf("kind = %v, want COMPONENT", r.Nodes[0].Kind)
	}
	if r.Nodes[0].Label != "MyApp" {
		t.Errorf("label = %q", r.Nodes[0].Label)
	}
}

func TestReactComponent_NoMatchOnPlainCode(t *testing.T) {
	d := NewReactComponentDetector()
	r := d.Detect(&detector.Context{FilePath: "x.ts", Language: "typescript", Content: "function lowercase() {}"})
	if len(r.Nodes) != 0 {
		t.Fatalf("expected 0 nodes, got %d", len(r.Nodes))
	}
}

func TestReactComponent_RendersEdgesScoped(t *testing.T) {
	src := `export const Header = () => {
  return <Button label="ok" />;
};

export const Footer = () => {
  return <Icon name="home" />;
};
`
	d := NewReactComponentDetector()
	r := d.Detect(&detector.Context{FilePath: "x.tsx", Language: "typescript", Content: src})
	if len(r.Nodes) != 2 {
		t.Fatalf("expected 2 nodes, got %d", len(r.Nodes))
	}
	var headerRenders, footerRenders []string
	for _, e := range r.Edges {
		if e.Kind != model.EdgeRenders {
			continue
		}
		if strings.Contains(e.SourceID, "Header") {
			headerRenders = append(headerRenders, e.TargetID)
		}
		if strings.Contains(e.SourceID, "Footer") {
			footerRenders = append(footerRenders, e.TargetID)
		}
	}
	if !containsStr(headerRenders, "Button") {
		t.Errorf("Header should render Button: %v", headerRenders)
	}
	if containsStr(headerRenders, "Icon") {
		t.Errorf("Header shouldn't render Icon")
	}
	if !containsStr(footerRenders, "Icon") {
		t.Errorf("Footer should render Icon: %v", footerRenders)
	}
	if containsStr(footerRenders, "Button") {
		t.Errorf("Footer shouldn't render Button")
	}
}

func TestReactComponent_SiblingPreserved(t *testing.T) {
	src := `export const Header = () => {
  return <Footer />;
};

export const Footer = () => {
  return <div />;
};
`
	d := NewReactComponentDetector()
	r := d.Detect(&detector.Context{FilePath: "x.tsx", Language: "typescript", Content: src})
	if len(r.Nodes) != 2 {
		t.Fatalf("expected 2 nodes, got %d", len(r.Nodes))
	}
	var headerRenders []string
	for _, e := range r.Edges {
		if e.Kind == model.EdgeRenders && strings.Contains(e.SourceID, "Header") {
			headerRenders = append(headerRenders, e.TargetID)
		}
	}
	if !containsStr(headerRenders, "Footer") {
		t.Errorf("Header should render Footer: %v", headerRenders)
	}
}

func TestReactComponent_NoSelfRender(t *testing.T) {
	src := `export const Recursive = () => {
  return <Recursive />;
};
`
	d := NewReactComponentDetector()
	r := d.Detect(&detector.Context{FilePath: "x.tsx", Language: "typescript", Content: src})
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 node, got %d", len(r.Nodes))
	}
	for _, e := range r.Edges {
		if e.Kind == model.EdgeRenders && e.TargetID == "Recursive" {
			t.Errorf("self-render edge emitted: %+v", e)
		}
	}
}

func TestReactComponent_SingleComponentJSX(t *testing.T) {
	src := `export default function Dashboard() {
  return (
    <Layout>
      <Sidebar />
      <MainContent />
    </Layout>
  );
}
`
	d := NewReactComponentDetector()
	r := d.Detect(&detector.Context{FilePath: "x.tsx", Language: "typescript", Content: src})
	if len(r.Nodes) != 1 {
		t.Fatalf("expected 1 node, got %d", len(r.Nodes))
	}
	var rendered []string
	for _, e := range r.Edges {
		if e.Kind == model.EdgeRenders {
			rendered = append(rendered, e.TargetID)
		}
	}
	if !containsStr(rendered, "Layout") || !containsStr(rendered, "Sidebar") || !containsStr(rendered, "MainContent") {
		t.Errorf("missing render targets: %v", rendered)
	}
}

func TestReactComponent_Deterministic(t *testing.T) {
	src := "export default function App() {}\nexport function useAuth() {}"
	d := NewReactComponentDetector()
	c := &detector.Context{FilePath: "x.tsx", Language: "typescript", Content: src}
	r1 := d.Detect(c)
	r2 := d.Detect(c)
	if len(r1.Nodes) != len(r2.Nodes) {
		t.Fatal("non-deterministic")
	}
}

func containsStr(s []string, want string) bool {
	for _, x := range s {
		if x == want {
			return true
		}
	}
	return false
}
