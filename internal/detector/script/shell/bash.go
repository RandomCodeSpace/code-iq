// Package shell holds Bash and PowerShell detectors.
package shell

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// BashDetector detects Bash script structure (functions, source imports,
// exports, and known CLI tool calls). Mirrors Java BashDetector.
type BashDetector struct{}

func NewBashDetector() *BashDetector { return &BashDetector{} }

func (BashDetector) Name() string                        { return "bash" }
func (BashDetector) SupportedLanguages() []string        { return []string{"bash"} }
func (BashDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewBashDetector()) }

var (
	bashFuncRE    = regexp.MustCompile(`(?:function\s+(\w+)|(\w+)\s*\(\s*\))\s*\{`)
	bashSourceRE  = regexp.MustCompile(`(?:source|\.) (?:")?([^\s"]+)`)
	bashShebangRE = regexp.MustCompile(`^#!\s*/(?:usr/)?(?:bin/)?(?:env\s+)?(\w+)`)
	bashExportRE  = regexp.MustCompile(`export\s+(\w+)=`)
	bashToolRE    = regexp.MustCompile(`\b(aws|az|docker|gcloud|kubectl|terraform)\b`)
)

func (d BashDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	fp := ctx.FilePath
	lines := strings.Split(text, "\n")
	seen := map[string]bool{}

	// Shebang → MODULE node for the script
	if len(lines) > 0 {
		if m := bashShebangRE.FindStringSubmatch(lines[0]); len(m) >= 2 {
			n := model.NewCodeNode(fp, model.NodeModule, fp)
			n.FQN = fp
			n.FilePath = fp
			n.LineStart = 1
			n.Source = "BashDetector"
			n.Properties["shell"] = m[1]
			nodes = append(nodes, n)
		}
	}

	for i, line := range lines {
		// Functions
		if m := bashFuncRE.FindStringSubmatch(line); len(m) >= 3 {
			funcName := m[1]
			if funcName == "" {
				funcName = m[2]
			}
			n := model.NewCodeNode(fp+":"+funcName, model.NodeMethod, funcName)
			n.FQN = funcName
			n.FilePath = fp
			n.LineStart = i + 1
			n.Source = "BashDetector"
			nodes = append(nodes, n)
		}

		// source ./lib.sh / . helpers.sh
		// Emit anchor nodes so the imports edge survives GraphBuilder's phantom-drop.
		if m := bashSourceRE.FindStringSubmatch(line); len(m) >= 2 {
			src := m[1]
			srcID := base.EnsureFileAnchor(ctx, "bash", "BashDetector", model.ConfidenceLexical, &nodes, seen)
			tgtID := base.EnsureExternalAnchor(src, "bash:external", "BashDetector", model.ConfidenceLexical, &nodes, seen)
			e := model.NewCodeEdge(srcID+":sources:"+tgtID, model.EdgeImports, srcID, tgtID)
			e.Source = "BashDetector"
			edges = append(edges, e)
		}

		// export VAR=...
		if m := bashExportRE.FindStringSubmatch(line); len(m) >= 2 {
			varName := m[1]
			n := model.NewCodeNode(fp+":export:"+varName, model.NodeConfigDefinition, "export "+varName)
			n.FQN = varName
			n.FilePath = fp
			n.LineStart = i + 1
			n.Source = "BashDetector"
			nodes = append(nodes, n)
		}
	}

	// Tool calls — dedup across the whole file, skip comments
	// Emit anchor nodes so the calls edges survive GraphBuilder's phantom-drop.
	toolsSeen := map[string]bool{}
	for _, line := range lines {
		stripped := strings.TrimLeft(line, " \t")
		if strings.HasPrefix(stripped, "#") {
			continue
		}
		for _, m := range bashToolRE.FindAllStringSubmatch(line, -1) {
			tool := m[1]
			if toolsSeen[tool] {
				continue
			}
			toolsSeen[tool] = true
			srcID := base.EnsureFileAnchor(ctx, "bash", "BashDetector", model.ConfidenceLexical, &nodes, seen)
			tgtID := base.EnsureExternalAnchor(tool, "bash:tool", "BashDetector", model.ConfidenceLexical, &nodes, seen)
			e := model.NewCodeEdge(srcID+":calls:"+tgtID, model.EdgeCalls, srcID, tgtID)
			e.Source = "BashDetector"
			e.Properties["tool"] = tool
			edges = append(edges, e)
		}
	}

	return detector.ResultOf(nodes, edges)
}
