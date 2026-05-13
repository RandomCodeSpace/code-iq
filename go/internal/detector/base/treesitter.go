package base

import (
	sitter "github.com/smacker/go-tree-sitter"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// TreeSitterDetectorDefaultConfidence is the floor for AST-backed detectors.
// Java equivalent: AbstractJavaParserDetector.defaultConfidence() = SYNTACTIC.
const TreeSitterDetectorDefaultConfidence = model.ConfidenceSyntactic

// Walk performs a pre-order DFS over the tree-sitter subtree rooted at root.
// The visitor returns false to abort the walk (siblings + descendants of the
// current node are still skipped if false is returned at that node).
func Walk(root *sitter.Node, visit func(*sitter.Node) bool) {
	if root == nil {
		return
	}
	if !visit(root) {
		return
	}
	for i := 0; i < int(root.NamedChildCount()); i++ {
		walkAborted := false
		Walk(root.NamedChild(i), func(n *sitter.Node) bool {
			if walkAborted {
				return false
			}
			ok := visit(n)
			if !ok {
				walkAborted = true
			}
			return ok
		})
	}
}

// FindFirstByType returns the first descendant whose type matches t (pre-order
// DFS). Returns nil when not found.
func FindFirstByType(root *sitter.Node, t string) *sitter.Node {
	var result *sitter.Node
	Walk(root, func(n *sitter.Node) bool {
		if n.Type() == t {
			result = n
			return false
		}
		return true
	})
	return result
}

// FindAllByType returns every descendant whose type matches t (pre-order DFS).
func FindAllByType(root *sitter.Node, t string) []*sitter.Node {
	var out []*sitter.Node
	Walk(root, func(n *sitter.Node) bool {
		if n.Type() == t {
			out = append(out, n)
		}
		return true
	})
	return out
}
