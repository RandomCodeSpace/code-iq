// Package extractor defines the LanguageExtractor interface and the Enricher
// orchestrator that drives per-language extractors over a node list.
//
// Mirrors src/main/java/.../intelligence/extractor/{LanguageExtractor,
// LanguageExtractionResult}.java. Each extractor is registered for one
// language and runs against nodes whose file path's extension maps to that
// language via DetectLanguage.
package extractor

import (
	"github.com/randomcodespace/codeiq/go/internal/model"
	"github.com/randomcodespace/codeiq/go/internal/parser"
)

// Context is the per-file context an extractor sees during enrich. The
// orchestrator reads the file once and passes the contents to every node-level
// Extract call for that file.
type Context struct {
	// FilePath is the path stamped onto CodeNode.FilePath (project-relative).
	FilePath string
	// Language is the canonical language key returned by Enricher.Language()
	// (lower-case, e.g. "java", "typescript").
	Language string
	// Content is the raw file source.
	Content string
	// Registry maps node ID and (when non-empty) FQN to the originating
	// CodeNode, so extractors can look up call targets, type bases, etc.
	Registry map[string]*model.CodeNode
}

// Result is what one extractor returns for one node. Mirrors
// LanguageExtractionResult in the Java tree.
type Result struct {
	// CallEdges holds CALLS-kind edges discovered for this node.
	CallEdges []*model.CodeEdge
	// SymbolReferences holds IMPORTS / DEPENDS_ON edges produced by import
	// or symbol-resolution heuristics.
	SymbolReferences []*model.CodeEdge
	// TypeHints stamps key/value strings into the node's Properties map.
	TypeHints map[string]string
	// Confidence is the capability-level confidence for this extraction.
	Confidence model.CapabilityLevel
}

// EmptyResult is the canonical zero result with PARTIAL confidence. Matches
// LanguageExtractionResult.empty() on the Java side.
func EmptyResult() Result {
	return Result{Confidence: model.CapabilityPartial}
}

// LanguageExtractor mirrors the Java LanguageExtractor interface. Implementors
// MUST be stateless and safe to call concurrently from multiple goroutines —
// the orchestrator fans out per-file work to a goroutine pool.
type LanguageExtractor interface {
	// Language returns the canonical language key, lower-case (e.g. "java").
	// This key must match DetectLanguage for the orchestrator to dispatch.
	Language() string
	// Extract runs the extractor against a single node, parsing ctx.Content
	// internally. Retained as the single-node convenience wrapper for tests
	// and ad-hoc callers; the orchestrator uses ExtractFromTree to avoid
	// re-parsing N times for a file with N nodes.
	Extract(ctx Context, node *model.CodeNode) Result
	// ExtractFromTree runs the extractor against every node in `nodes` using
	// a single pre-parsed tree. Returns one Result per input node in matching
	// order, so callers can stamp TypeHints back onto the corresponding node.
	// `tree` may be nil when ctx.Language has no tree-sitter grammar — the
	// extractor must handle that by returning len(nodes) EmptyResult entries.
	ExtractFromTree(ctx Context, tree *parser.Tree, nodes []*model.CodeNode) []Result
}
