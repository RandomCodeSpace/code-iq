package structured

import (
	"fmt"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// GitLabCiDetector mirrors Java GitLabCiDetector. Emits a pipeline MODULE +
// CONFIG_KEY per stage + METHOD per job, with CONTAINS / DEPENDS_ON /
// EXTENDS / IMPORTS edges for job needs, job extends, and include
// directives.
type GitLabCiDetector struct{}

func NewGitLabCiDetector() *GitLabCiDetector { return &GitLabCiDetector{} }

func (GitLabCiDetector) Name() string                        { return "gitlab_ci" }
func (GitLabCiDetector) SupportedLanguages() []string        { return []string{"yaml"} }
func (GitLabCiDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewGitLabCiDetector()) }

var gitlabKeywords = map[string]bool{
	"stages": true, "variables": true, "default": true, "workflow": true,
	"include": true, "image": true, "services": true, "before_script": true,
	"after_script": true, "cache": true,
}

var gitlabToolKeywords = []string{"docker", "helm", "kubectl", "terraform", "maven", "gradle", "npm", "pip"}

func (d GitLabCiDetector) Detect(ctx *detector.Context) *detector.Result {
	if !strings.HasSuffix(ctx.FilePath, ".gitlab-ci.yml") {
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
	pipelineID := "gitlab:" + fp + ":pipeline"
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	pn := model.NewCodeNode(pipelineID, model.NodeModule, "pipeline:"+fp)
	pn.FQN = pipelineID
	pn.Module = ctx.ModuleName
	pn.FilePath = fp
	pn.Confidence = base.StructuredDetectorDefaultConfidence
	pn.Properties["pipeline_file"] = fp
	nodes = append(nodes, pn)

	// Stages
	for _, s := range base.GetList(data, "stages") {
		stageStr := fmt.Sprint(s)
		sn := model.NewCodeNode("gitlab:"+fp+":stage:"+stageStr,
			model.NodeConfigKey, "stage:"+stageStr)
		sn.Module = ctx.ModuleName
		sn.FilePath = fp
		sn.Confidence = base.StructuredDetectorDefaultConfidence
		sn.Properties["stage"] = stageStr
		nodes = append(nodes, sn)
	}

	// Includes
	if includes, ok := data["include"]; ok && includes != nil {
		var incList []any
		switch t := includes.(type) {
		case string:
			incList = []any{t}
		case []any:
			incList = t
		}
		for _, inc := range incList {
			var target string
			switch v := inc.(type) {
			case string:
				target = v
			case map[string]any:
				if x, ok := v["local"]; ok && x != nil {
					target = fmt.Sprint(x)
				} else if x, ok := v["file"]; ok && x != nil {
					target = fmt.Sprint(x)
				} else if x, ok := v["template"]; ok && x != nil {
					target = fmt.Sprint(x)
				} else {
					target = fmt.Sprint(inc)
				}
			default:
				target = fmt.Sprint(inc)
			}
			edges = append(edges, model.NewCodeEdge(
				pipelineID+"->"+target, model.EdgeImports, pipelineID, target))
		}
	}

	// Collect job names (top-level map entries that aren't reserved keywords).
	var jobNames []string
	for k, v := range data {
		if gitlabKeywords[k] {
			continue
		}
		if _, ok := v.(map[string]any); ok {
			jobNames = append(jobNames, k)
		}
	}
	sort.Strings(jobNames)
	jobIDs := map[string]string{}
	for _, n := range jobNames {
		jobIDs[n] = "gitlab:" + fp + ":job:" + n
	}

	for _, jobName := range jobNames {
		jobDef := base.AsMap(data[jobName])
		jobID := jobIDs[jobName]
		props := map[string]any{}
		if stage := base.GetString(jobDef, "stage"); stage != "" {
			props["stage"] = stage
		}
		if image := base.GetString(jobDef, "image"); image != "" {
			props["image"] = image
		}
		scripts := base.GetList(jobDef, "script")
		tools := detectGitlabTools(scripts)
		if len(tools) > 0 {
			props["tools"] = tools
		}
		jn := model.NewCodeNode(jobID, model.NodeMethod, jobName)
		jn.FQN = jobID
		jn.Module = ctx.ModuleName
		jn.FilePath = fp
		jn.Confidence = base.StructuredDetectorDefaultConfidence
		for k, v := range props {
			jn.Properties[k] = v
		}
		nodes = append(nodes, jn)
		edges = append(edges, model.NewCodeEdge(
			pipelineID+"->"+jobID, model.EdgeContains, pipelineID, jobID))

		for _, dep := range toGitlabDepList(jobDef["needs"]) {
			if tgt, ok := jobIDs[dep]; ok {
				edges = append(edges, model.NewCodeEdge(
					jobID+"->"+tgt, model.EdgeDependsOn, jobID, tgt))
			}
		}
		for _, parent := range toStringList(jobDef["extends"]) {
			if tgt, ok := jobIDs[parent]; ok {
				edges = append(edges, model.NewCodeEdge(
					jobID+"->"+tgt, model.EdgeExtends, jobID, tgt))
			}
		}
	}
	return detector.ResultOf(nodes, edges)
}

func detectGitlabTools(scripts []any) []string {
	var tools []string
	for _, line := range scripts {
		lineStr := fmt.Sprint(line)
		for _, tool := range gitlabToolKeywords {
			if strings.Contains(lineStr, tool) {
				found := false
				for _, existing := range tools {
					if existing == tool {
						found = true
						break
					}
				}
				if !found {
					tools = append(tools, tool)
				}
			}
		}
	}
	return tools
}

func toGitlabDepList(v any) []string {
	switch t := v.(type) {
	case string:
		return []string{t}
	case []any:
		out := make([]string, 0, len(t))
		for _, item := range t {
			if m, ok := item.(map[string]any); ok {
				if job, ok := m["job"]; ok && job != nil {
					out = append(out, fmt.Sprint(job))
				}
			} else {
				out = append(out, fmt.Sprint(item))
			}
		}
		return out
	}
	return nil
}
