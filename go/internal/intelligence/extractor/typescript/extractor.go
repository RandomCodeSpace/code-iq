// Package typescript implements the TypeScript language extractor.
//
// Mirrors src/main/java/.../intelligence/extractor/typescript/TypeScriptLanguageExtractor.java.
// The tree-sitter TypeScript grammar parses .ts/.tsx and is also used (via
// the typescript alias in the orchestrator) for plain JavaScript files —
// the grammar is a superset.
//
// Capabilities:
//   - METHOD nodes: emit CALLS edges for call_expression children of the
//     matching function_declaration / method_definition / arrow_function.
//     Callee names come from the call_expression's `function` field.
//   - MODULE nodes: emit a `module_exports` type-hint listing every
//     export_statement declaration in the file.
//
// Confidence: PARTIAL.
package typescript

import (
	"fmt"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/intelligence/extractor"
	"github.com/randomcodespace/codeiq/go/internal/model"
	"github.com/randomcodespace/codeiq/go/internal/parser"
)

// Extractor implements LanguageExtractor for TypeScript. Stateless.
type Extractor struct{}

// New returns a TypeScript extractor.
func New() *Extractor { return &Extractor{} }

// Language returns "typescript".
func (e *Extractor) Language() string { return "typescript" }

// Extract dispatches by node kind: METHOD -> call edges, MODULE -> exports
// hint. Single-node convenience wrapper; production paths use ExtractFromTree.
func (e *Extractor) Extract(ctx extractor.Context, node *model.CodeNode) extractor.Result {
	tree, _ := parser.ParseByName("typescript", []byte(ctx.Content))
	if tree != nil {
		defer tree.Close()
	}
	out := e.ExtractFromTree(ctx, tree, []*model.CodeNode{node})
	if len(out) == 0 {
		return extractor.EmptyResult()
	}
	return out[0]
}

// ExtractFromTree walks the pre-parsed tree once per input node, returning
// one Result per node in matching order. tree may be nil.
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
	// collectExports only reads the tree root; compute once for all MODULE
	// nodes in the input.
	var moduleExports string
	var moduleExportsComputed bool
	for i, node := range nodes {
		if node == nil {
			continue
		}
		switch node.Kind {
		case model.NodeMethod:
			results[i] = extractor.Result{
				CallEdges:  collectCallEdges(root, ctx.Content, node, ctx.Registry),
				Confidence: model.CapabilityPartial,
			}
		case model.NodeModule:
			if !moduleExportsComputed {
				exports := collectExports(root, ctx.Content)
				if len(exports) > 0 {
					moduleExports = strings.Join(exports, ", ")
				}
				moduleExportsComputed = true
			}
			if moduleExports != "" {
				results[i] = extractor.Result{
					TypeHints:  map[string]string{"module_exports": moduleExports},
					Confidence: model.CapabilityPartial,
				}
			}
		}
	}
	return results
}

// collectCallEdges finds the function-like declaration matching fn.Label and
// emits one CALLS edge per call_expression whose `function` field resolves
// to a registry node (direct ID/FQN lookup, no ambiguity filtering — TS
// names are typically scoped enough to avoid the Java-style false-positive
// problem).
func collectCallEdges(root *parser.Node, src string, fn *model.CodeNode,
	registry map[string]*model.CodeNode) []*model.CodeEdge {
	if fn.Label == "" {
		return nil
	}
	var target *parser.Node
	parser.Walk(root, func(n *parser.Node) bool {
		if target != nil {
			return false
		}
		t := n.Type()
		if t != "function_declaration" && t != "method_definition" && t != "arrow_function" {
			return true
		}
		if name := parser.ChildFieldText(n, "name", src); name == fn.Label {
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
		if n.Type() != "call_expression" {
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
				"extractor_name": "typescript_language_extractor",
			},
		})
		return true
	})
	return edges
}

// collectExports enumerates each export_statement's declaration. For
// `export function foo() {}` the declaration is a function_declaration, for
// `export const bar = 1` it is a lexical_declaration; we return the raw
// text either way (mirrors Java side, which doesn't try to extract just the
// identifier).
func collectExports(root *parser.Node, src string) []string {
	var out []string
	parser.Walk(root, func(n *parser.Node) bool {
		if n.Type() != "export_statement" {
			return true
		}
		if text := parser.ChildFieldText(n, "declaration", src); text != "" {
			// Trim trailing semicolon / whitespace for readability.
			out = append(out, strings.TrimRight(strings.TrimSpace(text), ";"))
		}
		// Don't descend into the export — its declaration is the export node.
		return false
	})
	return out
}
