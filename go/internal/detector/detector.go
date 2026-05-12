package detector

import (
	"github.com/randomcodespace/codeiq/go/internal/model"
	"github.com/randomcodespace/codeiq/go/internal/parser"
)

// Detector is the contract every detector implements. Mirrors Java
// io.github.randomcodespace.iq.detector.Detector.
//
// Detectors must be stateless — phase 1 invokes each detector from goroutines
// concurrently. Use method-local state only.
type Detector interface {
	Name() string
	SupportedLanguages() []string
	// DefaultConfidence is the floor stamped onto every emission that does not
	// explicitly set Confidence — equivalent to Java's defaultConfidence().
	DefaultConfidence() model.Confidence
	Detect(ctx *Context) *Result
}

// Context is the per-file payload threaded through every Detect call.
// Mirrors Java DetectorContext.
type Context struct {
	FilePath   string
	Language   string
	Content    string
	Tree       *parser.Tree // nil for languages without a tree-sitter grammar
	ModuleName string
}

// Result is what a single Detect call returns. Mirrors Java DetectorResult.
type Result struct {
	Nodes []*model.CodeNode
	Edges []*model.CodeEdge
}

// EmptyResult returns an empty Result. Sentinel for "nothing matched".
func EmptyResult() *Result {
	return &Result{Nodes: nil, Edges: nil}
}

// ResultOf returns a Result with the given slices.
func ResultOf(nodes []*model.CodeNode, edges []*model.CodeEdge) *Result {
	return &Result{Nodes: nodes, Edges: edges}
}
