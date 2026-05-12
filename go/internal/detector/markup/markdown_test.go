package markup

import (
	"testing"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

const mdSource = `# My Project

This is a project.

## Setup

Run [setup](./scripts/setup.sh) first.

## API

See [API docs](docs/api.md#endpoints) for details.

Also see [external](https://example.com) — but this should not produce an edge.

### Internal heading

Some text.
`

func TestMarkdownPositive(t *testing.T) {
	d := NewMarkdownStructureDetector()
	r := d.Detect(&detector.Context{FilePath: "README.md", Language: "markdown", Content: mdSource})

	kinds := map[model.NodeKind]int{}
	for _, n := range r.Nodes {
		kinds[n.Kind]++
	}
	// 1 MODULE
	if kinds[model.NodeModule] != 1 {
		t.Errorf("expected 1 MODULE, got %d", kinds[model.NodeModule])
	}
	// H1 + 2 H2 + 1 H3 = 4 headings
	if kinds[model.NodeConfigKey] != 4 {
		t.Errorf("expected 4 CONFIG_KEY (headings), got %d", kinds[model.NodeConfigKey])
	}

	containsEdges := 0
	dependsEdges := 0
	for _, e := range r.Edges {
		switch e.Kind {
		case model.EdgeContains:
			containsEdges++
		case model.EdgeDependsOn:
			dependsEdges++
		}
	}
	// 4 headings → 4 CONTAINS edges
	if containsEdges != 4 {
		t.Errorf("expected 4 CONTAINS, got %d", containsEdges)
	}
	// 2 internal links (setup.sh, api.md) — external excluded
	if dependsEdges != 2 {
		t.Errorf("expected 2 DEPENDS_ON (internal links), got %d", dependsEdges)
	}
}

func TestMarkdownModuleLabelFromH1(t *testing.T) {
	d := NewMarkdownStructureDetector()
	r := d.Detect(&detector.Context{FilePath: "docs/README.md", Language: "markdown", Content: mdSource})
	for _, n := range r.Nodes {
		if n.Kind == model.NodeModule {
			if n.Label != "My Project" {
				t.Errorf("module label = %q, want %q", n.Label, "My Project")
			}
		}
	}
}

func TestMarkdownNoH1FallsBackToFilename(t *testing.T) {
	d := NewMarkdownStructureDetector()
	r := d.Detect(&detector.Context{
		FilePath: "notes/scratch.md",
		Language: "markdown",
		Content:  "Just some text without headings.\n",
	})
	for _, n := range r.Nodes {
		if n.Kind == model.NodeModule && n.Label != "scratch.md" {
			t.Errorf("module label = %q, want %q (filename fallback)", n.Label, "scratch.md")
		}
	}
}

func TestMarkdownNegative(t *testing.T) {
	d := NewMarkdownStructureDetector()
	r := d.Detect(&detector.Context{FilePath: "x.md", Language: "markdown", Content: ""})
	if len(r.Nodes) != 0 {
		t.Fatal("expected 0 nodes")
	}
}

func TestMarkdownDeterminism(t *testing.T) {
	d := NewMarkdownStructureDetector()
	ctx := &detector.Context{FilePath: "README.md", Language: "markdown", Content: mdSource}
	r1 := d.Detect(ctx)
	r2 := d.Detect(ctx)
	if len(r1.Nodes) != len(r2.Nodes) || len(r1.Edges) != len(r2.Edges) {
		t.Fatal("non-deterministic counts")
	}
}
