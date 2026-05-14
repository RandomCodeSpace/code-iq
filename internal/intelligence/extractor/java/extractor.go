// Package java implements the Java language extractor.
//
// Mirrors src/main/java/.../intelligence/extractor/java/JavaLanguageExtractor.java
// but uses the tree-sitter Java grammar (already wired in internal/parser)
// instead of JavaParser. Capabilities:
//
//   - METHOD nodes: emit CALLS edges for method_invocation children of the
//     matching method_declaration. Ambiguous-label callees (two distinct
//     METHOD nodes share a label) are dropped — same false-positive guard
//     as the Java side.
//   - CLASS / ABSTRACT_CLASS / INTERFACE nodes: emit type-hint properties
//     `extends_type` and `implements_types` from the matching
//     class/interface_declaration.
//
// Confidence: PARTIAL — the tree-sitter resolver isn't a full Java type
// checker, so we tag every emitted fact PARTIAL. The Edge.Confidence field
// (typed) stays LEXICAL; the "confidence":"PARTIAL" string lives in
// Properties for parity with the Java side's edge.properties map.
package java

import (
	"fmt"
	"strings"

	"github.com/randomcodespace/codeiq/internal/intelligence/extractor"
	"github.com/randomcodespace/codeiq/internal/model"
	"github.com/randomcodespace/codeiq/internal/parser"
)

// Extractor is the Java LanguageExtractor implementation. Stateless and
// safe for concurrent calls.
type Extractor struct{}

// New constructs a Java extractor.
func New() *Extractor { return &Extractor{} }

// Language returns "java".
func (e *Extractor) Language() string { return "java" }

// Extract returns CALLS edges for METHOD nodes and type-hierarchy hints for
// CLASS / ABSTRACT_CLASS / INTERFACE nodes. Single-node convenience wrapper —
// parses once per call. Production paths use ExtractFromTree.
func (e *Extractor) Extract(ctx extractor.Context, node *model.CodeNode) extractor.Result {
	tree, _ := parser.ParseByName("java", []byte(ctx.Content))
	if tree != nil {
		defer tree.Close()
	}
	out := e.ExtractFromTree(ctx, tree, []*model.CodeNode{node})
	if len(out) == 0 {
		return extractor.EmptyResult()
	}
	return out[0]
}

// ExtractFromTree walks the pre-parsed tree once per input node and returns
// one Result per node in matching order. tree may be nil — all results are
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
		case model.NodeClass, model.NodeAbstractClass, model.NodeInterface:
			hints := extractTypeHierarchyHints(root, ctx.Content, node.Label)
			results[i] = extractor.Result{
				TypeHints:  hints,
				Confidence: model.CapabilityPartial,
			}
		}
	}
	return results
}

// collectCallEdges walks the tree to locate the method_declaration whose
// name field matches methodNode.Label, then enumerates every method_invocation
// in its subtree and emits one CALLS edge per resolvable callee.
func collectCallEdges(root *parser.Node, content string, methodNode *model.CodeNode,
	registry map[string]*model.CodeNode) []*model.CodeEdge {
	if methodNode.Label == "" {
		return nil
	}
	var target *parser.Node
	parser.Walk(root, func(n *parser.Node) bool {
		if target != nil {
			return false
		}
		if n.Type() != "method_declaration" {
			return true
		}
		if name := parser.ChildFieldText(n, "name", content); name == methodNode.Label {
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
		if n.Type() != "method_invocation" {
			return true
		}
		callee := parser.ChildFieldText(n, "name", content)
		if callee == "" {
			return true
		}
		tgt := lookupSingleMatch(callee, registry)
		if tgt == nil || tgt.ID == methodNode.ID {
			return true
		}
		edges = append(edges, &model.CodeEdge{
			ID:         fmt.Sprintf("calls:%s:%s:%d", methodNode.ID, tgt.ID, int(n.StartPoint().Row)+1),
			Kind:       model.EdgeCalls,
			SourceID:   methodNode.ID,
			TargetID:   tgt.ID,
			Confidence: model.ConfidenceLexical,
			Properties: map[string]any{
				"confidence":     "PARTIAL",
				"extractor_name": "java_language_extractor",
			},
		})
		return true
	})
	return edges
}

// extractTypeHierarchyHints walks for the class/interface_declaration matching
// node.Label and returns its `extends_type` (single class) and
// `implements_types` (comma-separated list) from the corresponding fields.
//
// Tree-sitter returns the "extends" / "implements" keyword as part of the
// field text, so we strip those prefixes. The `interfaces` field wraps a
// `type_list` whose own child text is the bare comma-separated list — we
// prefer that child when present.
func extractTypeHierarchyHints(root *parser.Node, content, label string) map[string]string {
	hints := map[string]string{}
	parser.Walk(root, func(n *parser.Node) bool {
		t := n.Type()
		if t != "class_declaration" && t != "interface_declaration" {
			return true
		}
		// Match by label when the caller provided one; otherwise pick the
		// first declaration we encounter — matches Java's findFirst().
		if label != "" {
			if name := parser.ChildFieldText(n, "name", content); name != label {
				return true
			}
		}
		if sc := parser.ChildFieldText(n, "superclass", content); sc != "" {
			hints["extends_type"] = stripLeadingKeyword(sc, "extends")
		}
		if ifs := n.ChildByFieldName("interfaces"); ifs != nil {
			text := parser.NodeTextFromString(ifs, content)
			// Prefer the wrapped type_list child if present.
			if ifs.NamedChildCount() > 0 {
				inner := ifs.NamedChild(0)
				if inner != nil {
					text = parser.NodeTextFromString(inner, content)
				}
			}
			hints["implements_types"] = stripLeadingKeyword(text, "implements")
		}
		// Stop once we've found the matching declaration.
		return false
	})
	return hints
}

func stripLeadingKeyword(s, kw string) string {
	s = strings.TrimSpace(s)
	if strings.HasPrefix(s, kw) {
		s = strings.TrimSpace(s[len(kw):])
	}
	return s
}

// lookupSingleMatch returns the registry node iff exactly one METHOD node has
// the given label. Drops on ambiguity to avoid false-positive CALLS edges on
// common names like save/get/execute — same guard as Java's lookupByLabel.
func lookupSingleMatch(label string, registry map[string]*model.CodeNode) *model.CodeNode {
	var match *model.CodeNode
	for _, c := range registry {
		if c == nil || c.Label != label || c.Kind != model.NodeMethod {
			continue
		}
		if match != nil && match.ID != c.ID {
			return nil
		}
		match = c
	}
	return match
}
