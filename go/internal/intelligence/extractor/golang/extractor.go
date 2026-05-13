// Package golang implements the Go language extractor.
//
// The package is named `golang` (not `go`) to avoid the keyword collision in
// Go import paths — matches the smacker/go-tree-sitter/golang convention.
//
// Mirrors src/main/java/.../intelligence/extractor/go/GoLanguageExtractor.java
// but trimmed to the per-task brief:
//   - METHOD nodes: emit CALLS edges for call_expression children of the
//     matching function_declaration / method_declaration. Qualified callees
//     (`pkg.Func`) strip to the bare name before lookup so cross-package
//     calls resolve to METHOD nodes that registry-keyed by simple label.
//   - CLASS nodes: scan for `var _ Iface = (*Foo)(nil)` style interface
//     assertions and stamp `implements_types` with the interface qualifier
//     literal text.
//
// Confidence: PARTIAL — Go's structural typing isn't resolved here.
package golang

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/intelligence/extractor"
	"github.com/randomcodespace/codeiq/go/internal/model"
	"github.com/randomcodespace/codeiq/go/internal/parser"
)

// reInterfaceAssert matches the Go interface-satisfaction idiom:
//
//	var _ <iface> = (*<Struct>)(nil)
//
// The captured group is the interface qualifier — typically `io.Reader`,
// `pkg.Iface`, or a bare `Iface` from the same package.
var reInterfaceAssert = regexp.MustCompile(`var\s+_\s+(\S+)\s*=\s*\(\*\S+\)\(nil\)`)

// Extractor implements LanguageExtractor for Go. Stateless.
type Extractor struct{}

// New returns a Go extractor.
func New() *Extractor { return &Extractor{} }

// Language returns "go".
func (e *Extractor) Language() string { return "go" }

// Extract dispatches by node kind. Single-node convenience wrapper —
// production paths use ExtractFromTree.
func (e *Extractor) Extract(ctx extractor.Context, node *model.CodeNode) extractor.Result {
	tree, _ := parser.ParseByName("go", []byte(ctx.Content))
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
// one Result per node in matching order. tree may be nil — every result is
// EmptyResult in that case.
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
	// matchInterfaceAssertion only reads ctx.Content; compute once per file.
	var ifaceAssertion string
	var ifaceAssertionComputed bool
	for i, node := range nodes {
		if node == nil {
			continue
		}
		switch node.Kind {
		case model.NodeMethod:
			edges := collectGoCallEdges(root, ctx.Content, node, ctx.Registry)
			if len(edges) > 0 {
				results[i] = extractor.Result{
					CallEdges:  edges,
					Confidence: model.CapabilityPartial,
				}
			}
		case model.NodeClass:
			if !ifaceAssertionComputed {
				ifaceAssertion = matchInterfaceAssertion(ctx.Content)
				ifaceAssertionComputed = true
			}
			if ifaceAssertion != "" {
				results[i] = extractor.Result{
					TypeHints:  map[string]string{"implements_types": ifaceAssertion},
					Confidence: model.CapabilityPartial,
				}
			}
		}
	}
	return results
}

// matchInterfaceAssertion runs the package-level regex against the source. The
// regex is anchored on `var _ ... = (*...)(nil)` so it won't false-match
// regular var declarations.
func matchInterfaceAssertion(src string) string {
	m := reInterfaceAssert.FindStringSubmatch(src)
	if len(m) < 2 {
		return ""
	}
	return strings.TrimSpace(m[1])
}

// collectGoCallEdges finds the function_declaration / method_declaration
// whose name field matches fn.Label, then enumerates call_expressions in
// its subtree. Qualified callees like `pkg.Func` are stripped to `Func`
// for the registry lookup — matches the Java extractor's lookupByLabel
// strategy and keeps the registry key shape simple.
func collectGoCallEdges(root *parser.Node, src string, fn *model.CodeNode,
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
		if t != "function_declaration" && t != "method_declaration" {
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
		if n.Type() != "call_expression" {
			return true
		}
		callee := parser.ChildFieldText(n, "function", src)
		if callee == "" {
			return true
		}
		// Strip qualifier — `log.Println` -> `Println`. Registry keys by
		// simple label, so this is the only way cross-package METHOD
		// nodes are findable.
		if idx := strings.LastIndex(callee, "."); idx >= 0 {
			callee = callee[idx+1:]
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
				"extractor_name": "go_language_extractor",
			},
		})
		return true
	})
	return edges
}
