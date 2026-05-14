// Package python implements the Python language extractor.
//
// Mirrors src/main/java/.../intelligence/extractor/python/PythonLanguageExtractor.java
// using the tree-sitter Python grammar.
//
// Capabilities:
//   - METHOD nodes: emit CALLS edges for call nodes inside the matching
//     function_definition.
//   - CLASS nodes: emit `extends_type` type-hint from the first superclass.
//   - MODULE nodes: emit `all_exports` type-hint from a top-level __all__
//     list (regex-matched on the source — fast and correct for the common
//     module-level form).
//
// Confidence: PARTIAL.
package python

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/intelligence/extractor"
	"github.com/randomcodespace/codeiq/internal/model"
	"github.com/randomcodespace/codeiq/internal/parser"
)

// reAllList matches a module-level `__all__ = [...]` declaration. We use a
// regex rather than the AST because the assignment may appear at any scope
// in the file and the value-side is a Python literal that's cleaner to
// handle as plain text than via tree-walking.
var reAllList = regexp.MustCompile(`__all__\s*=\s*\[([^\]]*)\]`)

// Extractor implements LanguageExtractor for Python. Stateless.
type Extractor struct{}

// New returns a Python extractor.
func New() *Extractor { return &Extractor{} }

// Language returns "python".
func (e *Extractor) Language() string { return "python" }

// Extract dispatches by node kind. Single-node convenience wrapper; parses
// the file each call. Production paths use ExtractFromTree to amortise the
// parse across every node in a file.
func (e *Extractor) Extract(ctx extractor.Context, node *model.CodeNode) extractor.Result {
	tree, _ := parser.ParseByName("python", []byte(ctx.Content))
	if tree != nil {
		defer tree.Close()
	}
	out := e.ExtractFromTree(ctx, tree, []*model.CodeNode{node})
	if len(out) == 0 {
		return extractor.EmptyResult()
	}
	return out[0]
}

// ExtractFromTree walks a single pre-parsed tree once and produces a Result
// per input node. Order matches `nodes`. tree may be nil — every node maps
// to EmptyResult in that case.
func (e *Extractor) ExtractFromTree(ctx extractor.Context, tree *parser.Tree, nodes []*model.CodeNode) []extractor.Result {
	results := make([]extractor.Result, len(nodes))
	for i := range results {
		results[i] = extractor.EmptyResult()
	}
	if tree == nil || tree.Root == nil {
		return results
	}
	root := tree.Root.RootNode()
	if root == nil {
		return results
	}
	// matchAllList only reads ctx.Content; compute once per file and reuse
	// across every Module node in the input.
	var moduleAllExports string
	var moduleAllExportsComputed bool

	for i, node := range nodes {
		if node == nil {
			continue
		}
		switch node.Kind {
		case model.NodeMethod:
			edges := collectFunctionCallEdges(root, ctx.Content, node, ctx.Registry)
			if len(edges) > 0 {
				results[i] = extractor.Result{
					CallEdges:  edges,
					Confidence: model.CapabilityPartial,
				}
			}
		case model.NodeClass:
			if base := classBase(root, ctx.Content, node.Label); base != "" {
				results[i] = extractor.Result{
					TypeHints:  map[string]string{"extends_type": base},
					Confidence: model.CapabilityPartial,
				}
			}
		case model.NodeModule:
			if !moduleAllExportsComputed {
				moduleAllExports = matchAllList(ctx.Content)
				moduleAllExportsComputed = true
			}
			if moduleAllExports != "" {
				results[i] = extractor.Result{
					TypeHints:  map[string]string{"all_exports": moduleAllExports},
					Confidence: model.CapabilityPartial,
				}
			}
		}
	}
	return results
}

// matchAllList extracts the literal entries of a `__all__ = [...]` list as
// a comma-separated string of bare identifiers. Quotes and surrounding
// whitespace are stripped from each entry.
func matchAllList(src string) string {
	m := reAllList.FindStringSubmatch(src)
	if len(m) < 2 {
		return ""
	}
	parts := strings.Split(m[1], ",")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		p = strings.TrimSpace(p)
		p = strings.Trim(p, `"'`)
		if p == "" {
			continue
		}
		out = append(out, p)
	}
	return strings.Join(out, ", ")
}

// classBase locates the class_definition whose name matches `name` and
// returns the first identifier from its `superclasses` field — the
// tree-sitter Python grammar returns it as `(Bar)` text, so we trim parens.
// For multi-base classes (`class Foo(A, B):`) we return the comma-separated
// list as-is.
func classBase(root *parser.Node, src, name string) string {
	var found string
	parser.Walk(root, func(n *parser.Node) bool {
		if found != "" {
			return false
		}
		if n.Type() != "class_definition" {
			return true
		}
		if parser.ChildFieldText(n, "name", src) != name {
			return true
		}
		if base := parser.ChildFieldText(n, "superclasses", src); base != "" {
			found = strings.TrimSpace(strings.Trim(base, "()"))
		}
		return false
	})
	return found
}

// collectFunctionCallEdges finds the function_definition matching fn.Label
// and emits one CALLS edge per call expression whose `function` field
// resolves to a registry node.
func collectFunctionCallEdges(root *parser.Node, src string, fn *model.CodeNode,
	registry map[string]*model.CodeNode) []*model.CodeEdge {
	if fn.Label == "" {
		return nil
	}
	var target *parser.Node
	parser.Walk(root, func(n *parser.Node) bool {
		if target != nil {
			return false
		}
		if n.Type() != "function_definition" {
			return true
		}
		if parser.ChildFieldText(n, "name", src) == fn.Label {
			target = n
			return false
		}
		return true
	})
	if target == nil {
		return nil
	}
	var edges []*model.CodeEdge
	parser.Walk(target, func(n *parser.Node) bool {
		if n.Type() != "call" {
			return true
		}
		callee := parser.ChildFieldText(n, "function", src)
		if callee == "" {
			return true
		}
		tgt, ok := registry[callee]
		if !ok || tgt == nil || tgt.ID == fn.ID {
			return true
		}
		edges = append(edges, &model.CodeEdge{
			ID:         fmt.Sprintf("calls:%s:%s:%d", fn.ID, tgt.ID, int(n.StartPoint().Row)+1),
			Kind:       model.EdgeCalls,
			SourceID:   fn.ID,
			TargetID:   tgt.ID,
			Confidence: model.ConfidenceLexical,
			Properties: map[string]any{
				"confidence":     "PARTIAL",
				"extractor_name": "python_language_extractor",
			},
		})
		return true
	})
	return edges
}
