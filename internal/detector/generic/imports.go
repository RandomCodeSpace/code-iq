package generic

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// GenericImportsDetector emits MODULE nodes + IMPORTS edges for files in the
// phase-1 language set (java + python). Phase 4 will extend the language list
// to ruby/swift/perl/lua/dart/r and merge with the wider Java port.
type GenericImportsDetector struct{}

func NewGenericImportsDetector() *GenericImportsDetector { return &GenericImportsDetector{} }

func (GenericImportsDetector) Name() string                        { return "generic_imports" }
func (GenericImportsDetector) SupportedLanguages() []string        { return []string{"java", "python"} }
func (GenericImportsDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewGenericImportsDetector()) }

var (
	javaImportRE   = regexp.MustCompile(`(?m)^\s*import\s+(?:static\s+)?([\w.]+(?:\.\*)?)\s*;`)
	pythonImportRE = regexp.MustCompile(`(?m)^\s*import\s+([\w.]+)`)
	pythonFromRE   = regexp.MustCompile(`(?m)^\s*from\s+([\w.]+)\s+import\s+`)
)

func (d GenericImportsDetector) Detect(ctx *detector.Context) *detector.Result {
	switch ctx.Language {
	case "java":
		return d.detectJava(ctx)
	case "python":
		return d.detectPython(ctx)
	default:
		return detector.EmptyResult()
	}
}

func (d GenericImportsDetector) detectJava(ctx *detector.Context) *detector.Result {
	return d.emitImports(ctx, javaImportRE.FindAllStringSubmatchIndex(ctx.Content, -1))
}

func (d GenericImportsDetector) detectPython(ctx *detector.Context) *detector.Result {
	matches := pythonImportRE.FindAllStringSubmatchIndex(ctx.Content, -1)
	matches = append(matches, pythonFromRE.FindAllStringSubmatchIndex(ctx.Content, -1)...)
	return d.emitImports(ctx, matches)
}

// emitImports builds a synthetic MODULE node for the file and one MODULE node
// + IMPORTS edge per import target.
func (d GenericImportsDetector) emitImports(ctx *detector.Context, matches [][]int) *detector.Result {
	if len(matches) == 0 {
		return detector.EmptyResult()
	}
	fileNodeID := ctx.FilePath + ":file"
	fileNode := model.NewCodeNode(fileNodeID, model.NodeModule, ctx.FilePath)
	fileNode.FilePath = ctx.FilePath
	fileNode.Source = "GenericImportsDetector"
	fileNode.Confidence = model.ConfidenceLexical
	fileNode.Properties["language"] = ctx.Language

	nodes := []*model.CodeNode{fileNode}
	var edges []*model.CodeEdge
	seen := make(map[string]bool)
	for _, m := range matches {
		target := strings.TrimSpace(ctx.Content[m[2]:m[3]])
		if target == "" || seen[target] {
			continue
		}
		seen[target] = true

		targetID := "ext:" + target
		tnode := model.NewCodeNode(targetID, model.NodeModule, target)
		tnode.FQN = target
		tnode.Source = "GenericImportsDetector"
		tnode.Confidence = model.ConfidenceLexical
		tnode.Properties["external"] = true
		tnode.Properties["language"] = ctx.Language
		nodes = append(nodes, tnode)

		edgeID := fmt.Sprintf("%s->%s:imports", fileNodeID, targetID)
		e := model.NewCodeEdge(edgeID, model.EdgeImports, fileNodeID, targetID)
		e.Source = "GenericImportsDetector"
		e.Confidence = model.ConfidenceLexical
		e.Properties["module"] = target
		e.Properties["language"] = ctx.Language
		edges = append(edges, e)
	}
	return detector.ResultOf(nodes, edges)
}
