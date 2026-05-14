package structured

import (
	"fmt"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// GitHubActionsDetector mirrors Java GitHubActionsDetector. Emits one
// MODULE per workflow + CONFIG_KEY per trigger event + METHOD per job, with
// CONTAINS edges workflow→job and DEPENDS_ON edges from jobs to their needs.
//
// Gotcha (per CLAUDE.md): the YAML loader parses bare `on:` as boolean
// true; the Go yaml.v3 path coerces bool keys back to "true"/"false" in
// stringifyKey. We tolerate both "on" and "true" keys for the trigger map.
type GitHubActionsDetector struct{}

func NewGitHubActionsDetector() *GitHubActionsDetector { return &GitHubActionsDetector{} }

func (GitHubActionsDetector) Name() string                        { return "github_actions" }
func (GitHubActionsDetector) SupportedLanguages() []string        { return []string{"yaml"} }
func (GitHubActionsDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewGitHubActionsDetector()) }

func (d GitHubActionsDetector) Detect(ctx *detector.Context) *detector.Result {
	if !strings.Contains(ctx.FilePath, ".github/workflows/") {
		return detector.EmptyResult()
	}
	if ctx.ParsedData == nil {
		return detector.EmptyResult()
	}
	data := base.GetMap(ctx.ParsedData, "data")
	if len(data) == 0 {
		return detector.EmptyResult()
	}
	fp := ctx.FilePath
	workflowID := "gha:" + fp
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	workflowName := base.GetStringOrDefault(data, "name", fp)
	wn := model.NewCodeNode(workflowID, model.NodeModule, workflowName)
	wn.FQN = workflowID
	wn.Module = ctx.ModuleName
	wn.FilePath = fp
	wn.Confidence = base.StructuredDetectorDefaultConfidence
	wn.Properties["workflow_file"] = fp
	nodes = append(nodes, wn)

	// Trigger events from "on:" key. yaml.v3 may parse bare `on` as bool→"true".
	var onTriggers any
	if v, ok := data["on"]; ok {
		onTriggers = v
	} else if v, ok := data["true"]; ok {
		onTriggers = v
	}

	switch t := onTriggers.(type) {
	case string:
		nodes = append(nodes, makeTriggerNode(fp, t, ctx.ModuleName))
	case []any:
		for _, e := range t {
			nodes = append(nodes, makeTriggerNode(fp, fmt.Sprint(e), ctx.ModuleName))
		}
	case map[string]any:
		keys := make([]string, 0, len(t))
		for k := range t {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			nodes = append(nodes, makeTriggerNode(fp, k, ctx.ModuleName))
		}
	}

	// Jobs.
	jobs := base.GetMap(data, "jobs")
	if len(jobs) == 0 {
		return detector.ResultOf(nodes, edges)
	}
	jobNames := make([]string, 0, len(jobs))
	for n := range jobs {
		jobNames = append(jobNames, n)
	}
	sort.Strings(jobNames)
	jobIDs := map[string]string{}
	for _, n := range jobNames {
		jobIDs[n] = "gha:" + fp + ":job:" + n
	}
	for _, jobName := range jobNames {
		jobDef := base.AsMap(jobs[jobName])
		if len(jobDef) == 0 {
			continue
		}
		jobID := jobIDs[jobName]
		props := map[string]any{}
		if v, ok := jobDef["runs-on"]; ok && v != nil {
			props["runs_on"] = fmt.Sprint(v)
		}
		jobLabel := base.GetStringOrDefault(jobDef, "name", jobName)
		jn := model.NewCodeNode(jobID, model.NodeMethod, jobLabel)
		jn.FQN = jobID
		jn.Module = ctx.ModuleName
		jn.FilePath = fp
		jn.Confidence = base.StructuredDetectorDefaultConfidence
		for k, v := range props {
			jn.Properties[k] = v
		}
		nodes = append(nodes, jn)
		edges = append(edges, model.NewCodeEdge(
			workflowID+"->"+jobID, model.EdgeContains, workflowID, jobID))

		for _, dep := range toStringList(jobDef["needs"]) {
			if depID, ok := jobIDs[dep]; ok {
				edges = append(edges, model.NewCodeEdge(
					jobID+"->"+depID, model.EdgeDependsOn, jobID, depID))
			}
		}
	}
	return detector.ResultOf(nodes, edges)
}

func makeTriggerNode(fp, eventStr, moduleName string) *model.CodeNode {
	n := model.NewCodeNode("gha:"+fp+":trigger:"+eventStr,
		model.NodeConfigKey, "trigger: "+eventStr)
	n.Module = moduleName
	n.FilePath = fp
	n.Confidence = base.StructuredDetectorDefaultConfidence
	n.Properties["event"] = eventStr
	return n
}

func toStringList(v any) []string {
	switch t := v.(type) {
	case string:
		return []string{t}
	case []any:
		out := make([]string, 0, len(t))
		for _, item := range t {
			out = append(out, fmt.Sprint(item))
		}
		return out
	}
	return nil
}
