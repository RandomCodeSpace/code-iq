package shell

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// PowerShellDetector detects PowerShell script structure: functions
// (advanced/regular), Import-Module, dot-source imports, and parameters.
// Mirrors Java PowerShellDetector.
type PowerShellDetector struct{}

func NewPowerShellDetector() *PowerShellDetector { return &PowerShellDetector{} }

func (PowerShellDetector) Name() string                        { return "powershell" }
func (PowerShellDetector) SupportedLanguages() []string        { return []string{"powershell"} }
func (PowerShellDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewPowerShellDetector()) }

var (
	psFuncRE          = regexp.MustCompile(`(?i)function\s+([\w-]+)\s*(?:\([^)]*\))?\s*\{`)
	psImportRE        = regexp.MustCompile(`(?i)Import-Module\s+(\S+)`)
	psDotSourceRE     = regexp.MustCompile(`\.\s+["']?(\S+\.ps(?:1|m1))["']?`)
	psParamRE         = regexp.MustCompile(`\[Parameter[^\]]*\]\s*\[(\w+)\]\s*\$(\w+)`)
	psCmdletBindingRE = regexp.MustCompile(`(?i)\[CmdletBinding\(\)\]`)
)

func (d PowerShellDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	fp := ctx.FilePath
	lines := strings.Split(text, "\n")
	seen := map[string]bool{}

	for i, line := range lines {
		// Functions
		if m := psFuncRE.FindStringSubmatch(line); len(m) >= 2 {
			funcName := m[1]
			isAdvanced := false
			limit := i + 5
			if limit > len(lines) {
				limit = len(lines)
			}
			for j := i + 1; j < limit; j++ {
				if psCmdletBindingRE.MatchString(lines[j]) {
					isAdvanced = true
					break
				}
			}
			n := model.NewCodeNode(fp+":"+funcName, model.NodeMethod, funcName)
			n.FQN = funcName
			n.FilePath = fp
			n.LineStart = i + 1
			n.Source = "PowerShellDetector"
			if isAdvanced {
				n.Properties["advanced_function"] = true
			}
			nodes = append(nodes, n)
		}

		// Import-Module — emit anchor nodes so the imports edge survives
		// GraphBuilder's phantom-drop filter.
		if m := psImportRE.FindStringSubmatch(line); len(m) >= 2 {
			imp := m[1]
			srcID := base.EnsureFileAnchor(ctx, "powershell", "PowerShellDetector", model.ConfidenceLexical, &nodes, seen)
			tgtID := base.EnsureExternalAnchor(imp, "powershell:external", "PowerShellDetector", model.ConfidenceLexical, &nodes, seen)
			e := model.NewCodeEdge(srcID+":imports:"+tgtID, model.EdgeImports, srcID, tgtID)
			e.Source = "PowerShellDetector"
			edges = append(edges, e)
		}

		// . path\to\file.ps1 — emit anchor nodes so the imports edge survives.
		if m := psDotSourceRE.FindStringSubmatch(line); len(m) >= 2 {
			src := m[1]
			srcID := base.EnsureFileAnchor(ctx, "powershell", "PowerShellDetector", model.ConfidenceLexical, &nodes, seen)
			tgtID := base.EnsureExternalAnchor(src, "powershell:external", "PowerShellDetector", model.ConfidenceLexical, &nodes, seen)
			e := model.NewCodeEdge(srcID+":dotsource:"+tgtID, model.EdgeImports, srcID, tgtID)
			e.Source = "PowerShellDetector"
			edges = append(edges, e)
		}

		// [Parameter()] [type]$name
		if m := psParamRE.FindStringSubmatch(line); len(m) >= 3 {
			ptype := m[1]
			pname := m[2]
			n := model.NewCodeNode(fp+":param:"+pname, model.NodeConfigDefinition, "$"+pname+": "+ptype)
			n.FQN = pname
			n.FilePath = fp
			n.LineStart = i + 1
			n.Source = "PowerShellDetector"
			n.Properties["param_type"] = ptype
			nodes = append(nodes, n)
		}
	}

	return detector.ResultOf(nodes, edges)
}
