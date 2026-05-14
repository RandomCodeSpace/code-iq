package detector

import (
	"github.com/randomcodespace/codeiq/internal/model"
	"github.com/randomcodespace/codeiq/internal/parser"
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
	// ParsedData is the pre-parsed structured payload for YAML/JSON/TOML/INI/
	// properties files. Wrapped in the same envelope shape used by the Java
	// side: a map with keys "type" (e.g. "yaml", "yaml_multi", "json", "toml",
	// "ini", "properties") and "data" / "documents". nil for files that don't
	// participate in structured parsing.
	ParsedData map[string]any
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
