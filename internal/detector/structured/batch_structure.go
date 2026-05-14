package structured

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// BatchStructureDetector mirrors Java BatchStructureDetector. Emits a
// MODULE node for the file, a METHOD per :LABEL, a CONFIG_DEFINITION per
// SET variable, CONTAINS edges from the module to each label, and CALLS
// edges from the module to CALL targets.
type BatchStructureDetector struct{}

func NewBatchStructureDetector() *BatchStructureDetector { return &BatchStructureDetector{} }

func (BatchStructureDetector) Name() string                        { return "batch_structure" }
func (BatchStructureDetector) SupportedLanguages() []string        { return []string{"batch"} }
func (BatchStructureDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewBatchStructureDetector()) }

var (
	batLabelRE = regexp.MustCompile(`^:(\w+)`)
	batCallRE  = regexp.MustCompile(`(?i)CALL\s+:?(\S+)`)
	batSetRE   = regexp.MustCompile(`(?i)SET\s+(\w+)=`)
)

func (d BatchStructureDetector) Detect(ctx *detector.Context) *detector.Result {
	content := ctx.Content
	if content == "" {
		return detector.EmptyResult()
	}
	fp := ctx.FilePath
	moduleID := "bat:" + fp
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	mn := model.NewCodeNode(moduleID, model.NodeModule, fp)
	mn.FQN = fp
	mn.Module = ctx.ModuleName
	mn.FilePath = fp
	mn.LineStart = 1
	mn.Confidence = base.RegexDetectorDefaultConfidence
	nodes = append(nodes, mn)

	lines := strings.Split(content, "\n")
	for i, raw := range lines {
		lineNum := i + 1
		stripped := strings.TrimSpace(raw)
		if stripped == "" {
			continue
		}
		upper := strings.ToUpper(stripped)
		if strings.HasPrefix(upper, "@ECHO OFF") {
			continue
		}
		if strings.HasPrefix(upper, "REM ") || upper == "REM" {
			continue
		}
		if strings.HasPrefix(stripped, "::") {
			continue
		}
		// Labels
		if m := batLabelRE.FindStringSubmatch(stripped); m != nil {
			labelName := m[1]
			labelID := "bat:" + fp + ":label:" + labelName
			ln := model.NewCodeNode(labelID, model.NodeMethod, ":"+labelName)
			ln.FQN = fp + ":" + labelName
			ln.Module = ctx.ModuleName
			ln.FilePath = fp
			ln.LineStart = lineNum
			ln.Confidence = base.RegexDetectorDefaultConfidence
			nodes = append(nodes, ln)
			edges = append(edges, model.NewCodeEdge(
				moduleID+"->"+labelID, model.EdgeContains, moduleID, labelID))
			continue
		}
		// CALL
		if m := batCallRE.FindStringSubmatch(stripped); m != nil {
			target := m[1]
			var targetID string
			switch {
			case strings.HasPrefix(target, ":"):
				targetID = "bat:" + fp + ":label:" + target[1:]
			case strings.Contains(target, "."):
				targetID = target
			default:
				targetID = "bat:" + fp + ":label:" + target
			}
			edges = append(edges, model.NewCodeEdge(
				moduleID+"->"+targetID, model.EdgeCalls, moduleID, targetID))
		}
		// SET
		if m := batSetRE.FindStringSubmatch(stripped); m != nil {
			varName := m[1]
			vn := model.NewCodeNode("bat:"+fp+":set:"+varName,
				model.NodeConfigDefinition, "SET "+varName)
			vn.FQN = fp + ":" + varName
			vn.Module = ctx.ModuleName
			vn.FilePath = fp
			vn.LineStart = lineNum
			vn.Confidence = base.RegexDetectorDefaultConfidence
			vn.Properties["variable"] = varName
			nodes = append(nodes, vn)
		}
	}
	return detector.ResultOf(nodes, edges)
}
