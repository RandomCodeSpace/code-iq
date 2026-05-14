package base

import (
	"context"
	"testing"

	sitter "github.com/smacker/go-tree-sitter"
	"github.com/smacker/go-tree-sitter/python"
)

func TestWalkVisitsAllNodes(t *testing.T) {
	src := []byte("def f():\n  return 1\n")
	p := sitter.NewParser()
	p.SetLanguage(python.GetLanguage())
	tree, err := p.ParseCtx(context.Background(), nil, src)
	if err != nil {
		t.Fatal(err)
	}
	defer tree.Close()

	var types []string
	Walk(tree.RootNode(), func(n *sitter.Node) bool {
		types = append(types, n.Type())
		return true
	})
	// Sanity: root should be "module" and we should have visited at least
	// the function_definition node.
	if len(types) == 0 || types[0] != "module" {
		t.Fatalf("unexpected walk order: %v", types)
	}
	found := false
	for _, ty := range types {
		if ty == "function_definition" {
			found = true
			break
		}
	}
	if !found {
		t.Fatalf("walk did not visit function_definition; saw %v", types)
	}
}

func TestWalkAbortsOnFalse(t *testing.T) {
	src := []byte("def f():\n  return 1\n")
	p := sitter.NewParser()
	p.SetLanguage(python.GetLanguage())
	tree, _ := p.ParseCtx(context.Background(), nil, src)
	defer tree.Close()

	count := 0
	Walk(tree.RootNode(), func(n *sitter.Node) bool {
		count++
		return count < 2 // stop after the second visit
	})
	if count != 2 {
		t.Fatalf("Walk did not abort at count=2: count = %d", count)
	}
}
