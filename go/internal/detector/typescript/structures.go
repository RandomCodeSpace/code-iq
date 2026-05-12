package typescript

import (
	"regexp"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// TypeScriptStructuresDetector ports
// io.github.randomcodespace.iq.detector.typescript.TypeScriptStructuresDetector.
// Phase 4 = regex-only path; ANTLR/TS AST refinement is deferred to phase 5.
type TypeScriptStructuresDetector struct{}

func NewTypeScriptStructuresDetector() *TypeScriptStructuresDetector {
	return &TypeScriptStructuresDetector{}
}

func (TypeScriptStructuresDetector) Name() string { return "typescript_structures" }
func (TypeScriptStructuresDetector) SupportedLanguages() []string {
	return []string{"typescript", "javascript"}
}
func (TypeScriptStructuresDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewTypeScriptStructuresDetector()) }

var (
	tsInterfaceRE = regexp.MustCompile(`(?m)^\s*(?:export\s+)?interface\s+(\w+)`)
	// Allow optional <...> generic parameters between name and '='.
	tsTypeRE      = regexp.MustCompile(`(?m)^\s*(?:export\s+)?type\s+(\w+)\s*(?:<[^>]*>)?\s*=`)
	tsClassRE     = regexp.MustCompile(`(?m)^\s*(?:export\s+)?(?:abstract\s+)?class\s+(\w+)`)
	tsFuncRE      = regexp.MustCompile(`(?m)^\s*(?:export\s+)?(default\s+)?(?:(async)\s+)?function\s+(\w+)`)
	tsConstFuncRE = regexp.MustCompile(`(?m)^\s*(?:export\s+)?const\s+(\w+)\s*=\s*(?:(async)\s+)?\(`)
	tsEnumRE      = regexp.MustCompile(`(?m)^\s*(?:export\s+)?(?:const\s+)?enum\s+(\w+)`)
	tsImportRE    = regexp.MustCompile(`import\s+.*?\s+from\s+['"]([^'"]+)['"]`)
	tsNamespaceRE = regexp.MustCompile(`(?m)^\s*(?:export\s+)?namespace\s+(\w+)`)
)

func (d TypeScriptStructuresDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	fp := ctx.FilePath
	moduleName := ctx.ModuleName
	existing := make(map[string]bool)

	mk := func(id string, kind model.NodeKind, label string, line int, props map[string]any) *model.CodeNode {
		n := model.NewCodeNode(id, kind, label)
		n.Label = label
		n.FQN = label
		n.Module = moduleName
		n.FilePath = fp
		n.LineStart = line
		n.Source = "TypeScriptStructuresDetector"
		n.Confidence = model.ConfidenceLexical
		for k, v := range props {
			n.Properties[k] = v
		}
		return n
	}

	// Interfaces
	for _, m := range tsInterfaceRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		id := "ts:" + fp + ":interface:" + name
		if existing[id] {
			continue
		}
		existing[id] = true
		nodes = append(nodes, mk(id, model.NodeInterface, name, base.FindLineNumber(text, m[0]), nil))
	}

	// Type aliases (treated as CLASS in Java with type_alias=true)
	for _, m := range tsTypeRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		id := "ts:" + fp + ":type:" + name
		if existing[id] {
			continue
		}
		existing[id] = true
		nodes = append(nodes, mk(id, model.NodeClass, name, base.FindLineNumber(text, m[0]),
			map[string]any{"type_alias": true}))
	}

	// Classes
	for _, m := range tsClassRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		id := "ts:" + fp + ":class:" + name
		if existing[id] {
			continue
		}
		existing[id] = true
		nodes = append(nodes, mk(id, model.NodeClass, name, base.FindLineNumber(text, m[0]), nil))
	}

	// Named functions
	for _, m := range tsFuncRE.FindAllStringSubmatchIndex(text, -1) {
		isDefault := m[2] >= 0
		isAsync := m[4] >= 0
		name := text[m[6]:m[7]]
		id := "ts:" + fp + ":func:" + name
		if existing[id] {
			continue
		}
		existing[id] = true
		props := map[string]any{}
		if isDefault {
			props["default"] = true
		}
		if isAsync {
			props["async"] = true
		}
		nodes = append(nodes, mk(id, model.NodeMethod, name, base.FindLineNumber(text, m[0]), props))
	}

	// const arrow functions
	for _, m := range tsConstFuncRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		isAsync := m[4] >= 0
		id := "ts:" + fp + ":func:" + name
		if existing[id] {
			continue
		}
		existing[id] = true
		props := map[string]any{}
		if isAsync {
			props["async"] = true
		}
		nodes = append(nodes, mk(id, model.NodeMethod, name, base.FindLineNumber(text, m[0]), props))
	}

	// Enums
	for _, m := range tsEnumRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		id := "ts:" + fp + ":enum:" + name
		if existing[id] {
			continue
		}
		existing[id] = true
		nodes = append(nodes, mk(id, model.NodeEnum, name, base.FindLineNumber(text, m[0]), nil))
	}

	// Imports
	for _, m := range tsImportRE.FindAllStringSubmatchIndex(text, -1) {
		mod := text[m[2]:m[3]]
		e := model.NewCodeEdge(fp+"->imports->"+mod, model.EdgeImports, fp, mod)
		e.Source = "TypeScriptStructuresDetector"
		e.Confidence = model.ConfidenceLexical
		edges = append(edges, e)
	}

	// Namespaces
	for _, m := range tsNamespaceRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		id := "ts:" + fp + ":namespace:" + name
		if existing[id] {
			continue
		}
		existing[id] = true
		nodes = append(nodes, mk(id, model.NodeModule, name, base.FindLineNumber(text, m[0]), nil))
	}

	return detector.ResultOf(nodes, edges)
}
