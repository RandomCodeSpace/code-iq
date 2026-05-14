// Package cpp holds C/C++ detectors.
package cpp

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// StructuresDetector detects C/C++ classes, structs, enums, functions,
// namespaces, and #include statements. Mirrors Java CppStructuresDetector.
type StructuresDetector struct{}

func NewStructuresDetector() *StructuresDetector { return &StructuresDetector{} }

func (StructuresDetector) Name() string                        { return "cpp_structures" }
func (StructuresDetector) SupportedLanguages() []string        { return []string{"cpp", "c"} }
func (StructuresDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewStructuresDetector()) }

var (
	cppClassRE     = regexp.MustCompile(`(?:template\s*<[^>]*>\s*)?class\s+(\w+)(?:\s*:\s*(?:public|protected|private)\s+(\w+))?`)
	cppStructRE    = regexp.MustCompile(`struct\s+(\w+)(?:\s*:\s*(?:public|protected|private)\s+(\w+))?\s*\{`)
	cppNamespaceRE = regexp.MustCompile(`namespace\s+(\w+)\s*\{`)
	cppFuncRE      = regexp.MustCompile(`(?m)^(?:[\w:*&<>\s]+)\s+(\w+)\s*\([^)]*\)\s*(?:const\s*)?\{`)
	cppIncludeRE   = regexp.MustCompile(`#include\s+[<"]([^>"]+)[>"]`)
	cppEnumRE      = regexp.MustCompile(`enum\s+(?:class\s+)?(\w+)`)
)

func isCppForwardDeclaration(line string) bool {
	s := strings.TrimRight(line, " \t\n\r")
	return strings.HasSuffix(s, ";") && !strings.Contains(s, "{")
}

func (d StructuresDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	fp := ctx.FilePath
	lines := strings.Split(text, "\n")
	seen := map[string]bool{}

	// #include statements → IMPORTS edges — emit anchor nodes so the edges
	// survive GraphBuilder's phantom-drop. Pre-fix, both endpoints were
	// free-form strings (file path and header path) with no matching
	// CodeNode anywhere.
	for _, line := range lines {
		if m := cppIncludeRE.FindStringSubmatch(line); len(m) >= 2 {
			imp := m[1]
			srcID := base.EnsureFileAnchor(ctx, "cpp", "CppStructuresDetector", model.ConfidenceLexical, &nodes, seen)
			tgtID := base.EnsureExternalAnchor(imp, "cpp:external", "CppStructuresDetector", model.ConfidenceLexical, &nodes, seen)
			e := model.NewCodeEdge(srcID+"->imports->"+tgtID, model.EdgeImports, srcID, tgtID)
			e.Source = "CppStructuresDetector"
			e.Confidence = model.ConfidenceLexical
			edges = append(edges, e)
		}
	}

	// Namespaces
	for i, line := range lines {
		if m := cppNamespaceRE.FindStringSubmatch(line); len(m) >= 2 {
			name := m[1]
			n := model.NewCodeNode(fp+":"+name, model.NodeModule, name)
			n.FQN = name
			n.FilePath = fp
			n.LineStart = i + 1
			n.Source = "CppStructuresDetector"
			n.Properties["namespace"] = true
			nodes = append(nodes, n)
		}
	}

	// Classes
	for i, line := range lines {
		if isCppForwardDeclaration(line) {
			continue
		}
		m := cppClassRE.FindStringSubmatch(line)
		if len(m) < 2 {
			continue
		}
		className := m[1]
		var baseClass string
		if len(m) >= 3 {
			baseClass = m[2]
		}
		isTemplate := strings.Contains(line, "template")
		nodeID := fp + ":" + className
		n := model.NewCodeNode(nodeID, model.NodeClass, className)
		n.FQN = className
		n.FilePath = fp
		n.LineStart = i + 1
		n.Source = "CppStructuresDetector"
		if isTemplate {
			n.Properties["is_template"] = true
		}
		nodes = append(nodes, n)

		if baseClass != "" {
			e := model.NewCodeEdge(
				nodeID+":extends:"+baseClass, model.EdgeExtends, nodeID, baseClass,
			)
			e.Source = "CppStructuresDetector"
			edges = append(edges, e)
		}
	}

	// Structs (also stored as CLASS kind, matching Java)
	for i, line := range lines {
		if isCppForwardDeclaration(line) {
			continue
		}
		m := cppStructRE.FindStringSubmatch(line)
		if len(m) < 2 {
			continue
		}
		structName := m[1]
		var baseStruct string
		if len(m) >= 3 {
			baseStruct = m[2]
		}
		nodeID := fp + ":" + structName
		n := model.NewCodeNode(nodeID, model.NodeClass, structName)
		n.FQN = structName
		n.FilePath = fp
		n.LineStart = i + 1
		n.Source = "CppStructuresDetector"
		n.Properties["struct"] = true
		nodes = append(nodes, n)

		if baseStruct != "" {
			e := model.NewCodeEdge(
				nodeID+":extends:"+baseStruct, model.EdgeExtends, nodeID, baseStruct,
			)
			e.Source = "CppStructuresDetector"
			edges = append(edges, e)
		}
	}

	// Enums
	for i, line := range lines {
		if isCppForwardDeclaration(line) {
			continue
		}
		m := cppEnumRE.FindStringSubmatch(line)
		if len(m) < 2 {
			continue
		}
		name := m[1]
		n := model.NewCodeNode(fp+":"+name, model.NodeEnum, name)
		n.FQN = name
		n.FilePath = fp
		n.LineStart = i + 1
		n.Source = "CppStructuresDetector"
		nodes = append(nodes, n)
	}

	// Functions (multi-line regex over whole text)
	for _, m := range cppFuncRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode(fp+":"+name, model.NodeMethod, name)
		n.FQN = name
		n.FilePath = fp
		n.LineStart = line
		n.Source = "CppStructuresDetector"
		nodes = append(nodes, n)
	}

	return detector.ResultOf(nodes, edges)
}
