package python

import (
	"fmt"
	"regexp"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// CeleryTaskDetector ports
// io.github.randomcodespace.iq.detector.python.CeleryTaskDetector.
type CeleryTaskDetector struct{}

func NewCeleryTaskDetector() *CeleryTaskDetector { return &CeleryTaskDetector{} }

func (CeleryTaskDetector) Name() string                        { return "python.celery_tasks" }
func (CeleryTaskDetector) SupportedLanguages() []string        { return []string{"python"} }
func (CeleryTaskDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewCeleryTaskDetector()) }

var (
	// Captures: optional name='<task_name>' kwarg, then the def function name.
	// (?s) is dot-all; first group is optional name kwarg, second is function.
	celeryTaskDecoratorRE = regexp.MustCompile(
		`(?s)@(?:\w+\.)?(?:task|shared_task)\(?(?:.*?name\s*=\s*['"]([^'"]+)['"])?[^)]*\)?\s*\n\s*def\s+(\w+)`)
	celeryCallRE = regexp.MustCompile(`(\w+)\.(delay|apply_async|s|si|signature)\(`)
)

func (d CeleryTaskDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName

	for _, m := range celeryTaskDecoratorRE.FindAllStringSubmatchIndex(text, -1) {
		taskName := ""
		if m[2] >= 0 {
			taskName = text[m[2]:m[3]]
		}
		funcName := text[m[4]:m[5]]
		if taskName == "" {
			taskName = funcName
		}
		line := base.FindLineNumber(text, m[0])

		queueID := fmt.Sprintf("queue:%s:celery:%s", moduleName, taskName)
		methodID := fmt.Sprintf("method:%s::%s", filePath, funcName)

		qn := model.NewCodeNode(queueID, model.NodeQueue, "celery:"+taskName)
		qn.Module = moduleName
		qn.FilePath = filePath
		qn.LineStart = line
		qn.Source = "CeleryTaskDetector"
		qn.Confidence = model.ConfidenceLexical
		qn.Properties["broker"] = "celery"
		qn.Properties["task_name"] = taskName
		qn.Properties["function"] = funcName
		nodes = append(nodes, qn)

		mn := model.NewCodeNode(methodID, model.NodeMethod, funcName)
		mn.FQN = filePath + "::" + funcName
		mn.Module = moduleName
		mn.FilePath = filePath
		mn.LineStart = line
		mn.Source = "CeleryTaskDetector"
		mn.Confidence = model.ConfidenceLexical
		nodes = append(nodes, mn)

		e := model.NewCodeEdge(methodID+"->consumes->"+queueID, model.EdgeConsumes, methodID, queueID)
		e.Source = "CeleryTaskDetector"
		e.Confidence = model.ConfidenceLexical
		edges = append(edges, e)
	}

	for _, m := range celeryCallRE.FindAllStringSubmatchIndex(text, -1) {
		taskRef := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		queueID := fmt.Sprintf("queue:%s:celery:%s", moduleName, taskRef)
		callerID := fmt.Sprintf("method:%s::caller_l%d", filePath, line)
		e := model.NewCodeEdge(callerID+"->produces->"+queueID, model.EdgeProduces, callerID, queueID)
		e.Source = "CeleryTaskDetector"
		e.Confidence = model.ConfidenceLexical
		edges = append(edges, e)
	}

	return detector.ResultOf(nodes, edges)
}
