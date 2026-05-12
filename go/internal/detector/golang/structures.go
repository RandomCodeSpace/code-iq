// Package golang holds Go-language detectors. Named "golang" rather than "go"
// to avoid the awkwardness of a directory literally called "go" inside a Go
// project where "go" is also the binary and a reserved package name in some
// tooling. Matches the convention already in use under
// intelligence/extractor/golang/.
package golang

import (
	"regexp"
	"unicode"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// StructuresDetector detects Go packages, structs, interfaces, methods, and
// functions. Mirrors Java GoStructuresDetector — regex-only (Phase 1 of the
// Java side also defaults to regex).
type StructuresDetector struct{}

func NewStructuresDetector() *StructuresDetector { return &StructuresDetector{} }

func (StructuresDetector) Name() string                        { return "go_structures" }
func (StructuresDetector) SupportedLanguages() []string        { return []string{"go"} }
func (StructuresDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewStructuresDetector()) }

var (
	goPackageRE     = regexp.MustCompile(`(?m)^package\s+(\w+)`)
	goImportSingle  = regexp.MustCompile(`(?m)^import\s+"([^"]+)"`)
	goImportBlock   = regexp.MustCompile(`(?s)import\s*\((.*?)\)`)
	goImportPath    = regexp.MustCompile(`"([^"]+)"`)
	goStructRE      = regexp.MustCompile(`type\s+(\w+)\s+struct\s*\{`)
	goInterfaceRE   = regexp.MustCompile(`type\s+(\w+)\s+interface\s*\{`)
	goMethodRE      = regexp.MustCompile(`func\s+\(\s*\w+\s+\*?(\w+)\s*\)\s+(\w+)\s*\(`)
	goFuncRE        = regexp.MustCompile(`(?m)^func\s+(\w+)\s*\(`)
)

func isExported(name string) bool {
	if name == "" {
		return false
	}
	return unicode.IsUpper(rune(name[0]))
}

func (d StructuresDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath

	// Package
	var pkgName string
	if m := goPackageRE.FindStringSubmatchIndex(text); len(m) >= 4 {
		pkgName = text[m[2]:m[3]]
		n := model.NewCodeNode(filePath+":package:"+pkgName, model.NodeModule, pkgName)
		n.FQN = pkgName
		n.FilePath = filePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "GoStructuresDetector"
		n.Properties["package"] = pkgName
		nodes = append(nodes, n)
	}

	// Single imports
	for _, m := range goImportSingle.FindAllStringSubmatchIndex(text, -1) {
		imp := text[m[2]:m[3]]
		edges = append(edges, mkImportEdge(filePath, imp))
	}

	// Block imports
	for _, b := range goImportBlock.FindAllStringSubmatchIndex(text, -1) {
		inner := text[b[2]:b[3]]
		for _, m := range goImportPath.FindAllStringSubmatchIndex(inner, -1) {
			imp := inner[m[2]:m[3]]
			edges = append(edges, mkImportEdge(filePath, imp))
		}
	}

	// Structs
	for _, m := range goStructRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := model.NewCodeNode(filePath+":"+name, model.NodeClass, name)
		n.FQN = qualify(pkgName, name)
		n.FilePath = filePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "GoStructuresDetector"
		n.Properties["exported"] = isExported(name)
		n.Properties["type"] = "struct"
		nodes = append(nodes, n)
	}

	// Interfaces
	for _, m := range goInterfaceRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := model.NewCodeNode(filePath+":"+name, model.NodeInterface, name)
		n.FQN = qualify(pkgName, name)
		n.FilePath = filePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "GoStructuresDetector"
		n.Properties["exported"] = isExported(name)
		nodes = append(nodes, n)
	}

	// Methods (receiver functions). Track positions to exclude from FUNC scan.
	methodStarts := map[int]bool{}
	for _, m := range goMethodRE.FindAllStringSubmatchIndex(text, -1) {
		methodStarts[m[0]] = true
		receiver := text[m[2]:m[3]]
		methodName := text[m[4]:m[5]]
		mid := filePath + ":" + receiver + ":" + methodName
		n := model.NewCodeNode(mid, model.NodeMethod, receiver+"."+methodName)
		n.FQN = qualify(pkgName, receiver+"."+methodName)
		n.FilePath = filePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "GoStructuresDetector"
		n.Properties["exported"] = isExported(methodName)
		n.Properties["receiver_type"] = receiver
		nodes = append(nodes, n)

		// DEFINES edge: struct/interface -> method
		eid := filePath + ":" + receiver + ":defines:" + methodName
		e := model.NewCodeEdge(eid, model.EdgeDefines, filePath+":"+receiver, mid)
		e.Source = "GoStructuresDetector"
		edges = append(edges, e)
	}

	// Package-level functions
	for _, m := range goFuncRE.FindAllStringSubmatchIndex(text, -1) {
		if methodStarts[m[0]] {
			continue
		}
		funcName := text[m[2]:m[3]]
		n := model.NewCodeNode(filePath+":"+funcName, model.NodeMethod, funcName)
		n.FQN = qualify(pkgName, funcName)
		n.FilePath = filePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "GoStructuresDetector"
		n.Properties["exported"] = isExported(funcName)
		n.Properties["type"] = "function"
		nodes = append(nodes, n)
	}

	return detector.ResultOf(nodes, edges)
}

func qualify(pkg, name string) string {
	if pkg == "" {
		return name
	}
	return pkg + "." + name
}

func mkImportEdge(filePath, imp string) *model.CodeEdge {
	e := model.NewCodeEdge(filePath+":imports:"+imp, model.EdgeImports, filePath, imp)
	e.Source = "GoStructuresDetector"
	return e
}
