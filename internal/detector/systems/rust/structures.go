// Package rust holds Rust-language detectors.
package rust

import (
	"regexp"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// StructuresDetector detects Rust modules, structs, traits, impls, functions,
// enums, macros, and use statements. Mirrors Java RustStructuresDetector.
type StructuresDetector struct{}

func NewStructuresDetector() *StructuresDetector { return &StructuresDetector{} }

func (StructuresDetector) Name() string                        { return "rust_structures" }
func (StructuresDetector) SupportedLanguages() []string        { return []string{"rust"} }
func (StructuresDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewStructuresDetector()) }

var (
	rustUseRE    = regexp.MustCompile(`(?m)^\s*use\s+([\w:]+)`)
	rustStructRE = regexp.MustCompile(`(?m)^\s*(?:pub\s+)?struct\s+(\w+)`)
	rustTraitRE  = regexp.MustCompile(`(?m)^\s*(?:pub\s+)?trait\s+(\w+)`)
	rustImplRE   = regexp.MustCompile(`(?m)^\s*impl(?:<[^>]*>)?\s+(\w+)(?:\s+for\s+(\w+))?\s*\{`)
	rustFnRE     = regexp.MustCompile(`(?m)^\s*(?:pub(?:\([^)]*\))?\s+)?(?:async\s+)?(?:unsafe\s+)?fn\s+(\w+)\s*\(`)
	rustModRE    = regexp.MustCompile(`(?m)^\s*(?:pub\s+)?mod\s+(\w+)`)
	rustEnumRE   = regexp.MustCompile(`(?m)^\s*(?:pub\s+)?enum\s+(\w+)`)
	rustMacroRE  = regexp.MustCompile(`(?m)^\s*macro_rules!\s+(\w+)`)
)

func (d StructuresDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	fp := ctx.FilePath
	seen := map[string]bool{}

	// use imports — emit anchor nodes so the edges survive GraphBuilder's
	// phantom-drop. Pre-fix, both endpoints were free-form strings (file
	// path and module path) with no matching CodeNode anywhere.
	for _, m := range rustUseRE.FindAllStringSubmatchIndex(text, -1) {
		target := text[m[2]:m[3]]
		srcID := base.EnsureFileAnchor(ctx, "rust", "RustStructuresDetector", model.ConfidenceLexical, &nodes, seen)
		tgtID := base.EnsureExternalAnchor(target, "rust:external", "RustStructuresDetector", model.ConfidenceLexical, &nodes, seen)
		e := model.NewCodeEdge(srcID+"->imports->"+tgtID, model.EdgeImports, srcID, tgtID)
		e.Source = "RustStructuresDetector"
		e.Confidence = model.ConfidenceLexical
		edges = append(edges, e)
	}

	// mod declarations
	for _, m := range rustModRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := model.NewCodeNode(fp+":mod:"+name, model.NodeModule, name)
		n.FQN = name
		n.FilePath = fp
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "RustStructuresDetector"
		nodes = append(nodes, n)
	}

	// structs
	for _, m := range rustStructRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := model.NewCodeNode(fp+":"+name, model.NodeClass, name)
		n.FQN = name
		n.FilePath = fp
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "RustStructuresDetector"
		n.Properties["type"] = "struct"
		nodes = append(nodes, n)
	}

	// traits
	for _, m := range rustTraitRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := model.NewCodeNode(fp+":"+name, model.NodeInterface, name)
		n.FQN = name
		n.FilePath = fp
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "RustStructuresDetector"
		n.Properties["type"] = "trait"
		nodes = append(nodes, n)
	}

	// enums
	for _, m := range rustEnumRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := model.NewCodeNode(fp+":"+name, model.NodeEnum, name)
		n.FQN = name
		n.FilePath = fp
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "RustStructuresDetector"
		nodes = append(nodes, n)
	}

	// impls: `impl X for Y` → IMPLEMENTS Y→X; `impl X` → DEFINES X→X
	for _, m := range rustImplRE.FindAllStringSubmatchIndex(text, -1) {
		first := text[m[2]:m[3]]
		var second string
		if m[4] >= 0 {
			second = text[m[4]:m[5]]
		}
		if second != "" {
			e := model.NewCodeEdge(
				fp+":"+second+":implements:"+first,
				model.EdgeImplements, fp+":"+second, fp+":"+first,
			)
			e.Source = "RustStructuresDetector"
			edges = append(edges, e)
		} else {
			e := model.NewCodeEdge(
				fp+":"+first+":defines:"+first,
				model.EdgeDefines, fp+":"+first, fp+":"+first,
			)
			e.Source = "RustStructuresDetector"
			edges = append(edges, e)
		}
	}

	// functions
	for _, m := range rustFnRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := model.NewCodeNode(fp+":"+name, model.NodeMethod, name)
		n.FQN = name
		n.FilePath = fp
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "RustStructuresDetector"
		n.Properties["type"] = "function"
		nodes = append(nodes, n)
	}

	// macros
	for _, m := range rustMacroRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := model.NewCodeNode(fp+":macro:"+name, model.NodeMethod, name+"!")
		n.FQN = name + "!"
		n.FilePath = fp
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "RustStructuresDetector"
		n.Properties["type"] = "macro"
		nodes = append(nodes, n)
	}

	return detector.ResultOf(nodes, edges)
}
