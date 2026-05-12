package structured

import (
	"regexp"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// HelmChartDetector mirrors Java HelmChartDetector. Three modes by filename:
// Chart.yaml (chart + dep MODULE nodes + DEPENDS_ON), values.yaml under a
// charts/ or helm/ path (CONFIG_KEY per top-level), and templates/*.yaml
// (regex scan for {{ .Values.x }} READS_CONFIG and {{ include "x" }}
// IMPORTS edges).
type HelmChartDetector struct{}

func NewHelmChartDetector() *HelmChartDetector { return &HelmChartDetector{} }

func (HelmChartDetector) Name() string                        { return "helm_chart" }
func (HelmChartDetector) SupportedLanguages() []string        { return []string{"yaml"} }
func (HelmChartDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewHelmChartDetector()) }

var (
	helmValuesRefRE = regexp.MustCompile(`\{\{\s*\.Values\.([a-zA-Z0-9_.]+)\s*\}\}`)
	helmIncludeRE   = regexp.MustCompile(`\{\{-?\s*include\s+["']([^"']+)["']`)
)

func (d HelmChartDetector) Detect(ctx *detector.Context) *detector.Result {
	fp := ctx.FilePath
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	switch {
	case strings.HasSuffix(fp, "Chart.yaml"):
		d.detectChartYaml(ctx, &nodes, &edges)
	case strings.HasSuffix(fp, "values.yaml") && (strings.Contains(fp, "charts/") || strings.Contains(fp, "helm/")):
		d.detectValuesYaml(ctx, &nodes, &edges)
	case strings.Contains(fp, "/templates/") && strings.HasSuffix(fp, ".yaml"):
		d.detectTemplate(ctx, &nodes, &edges)
	default:
		return detector.EmptyResult()
	}
	return detector.ResultOf(nodes, edges)
}

func (d HelmChartDetector) detectChartYaml(ctx *detector.Context, nodes *[]*model.CodeNode, edges *[]*model.CodeEdge) {
	fp := ctx.FilePath
	data := getHelmYAMLData(ctx)
	if data == nil {
		return
	}
	chartName := base.GetStringOrDefault(data, "name", "unknown")
	chartVersion := base.GetStringOrDefault(data, "version", "0.0.0")
	chartID := "helm:" + fp + ":chart:" + chartName

	cn := model.NewCodeNode(chartID, model.NodeModule, "helm:"+chartName)
	cn.FQN = "helm:" + chartName + ":" + chartVersion
	cn.Module = ctx.ModuleName
	cn.FilePath = fp
	cn.Confidence = base.StructuredDetectorDefaultConfidence
	cn.Properties["chart_name"] = chartName
	cn.Properties["chart_version"] = chartVersion
	cn.Properties["type"] = "helm_chart"
	*nodes = append(*nodes, cn)

	for _, dep := range base.GetList(data, "dependencies") {
		depMap := base.AsMap(dep)
		if depMap == nil {
			continue
		}
		depName := base.GetString(depMap, "name")
		if depName == "" {
			continue
		}
		depVersion := base.GetStringOrDefault(depMap, "version", "")
		depRepo := base.GetStringOrDefault(depMap, "repository", "")
		depID := "helm:" + fp + ":dep:" + depName

		dn := model.NewCodeNode(depID, model.NodeModule, "helm-dep:"+depName)
		dn.FQN = "helm:" + depName + ":" + depVersion
		dn.Module = ctx.ModuleName
		dn.FilePath = fp
		dn.Confidence = base.StructuredDetectorDefaultConfidence
		dn.Properties["chart_name"] = depName
		dn.Properties["chart_version"] = depVersion
		dn.Properties["repository"] = depRepo
		dn.Properties["type"] = "helm_dependency"
		*nodes = append(*nodes, dn)

		e := model.NewCodeEdge(chartID+"->"+depID, model.EdgeDependsOn, chartID, depID)
		e.Confidence = base.StructuredDetectorDefaultConfidence
		e.Properties["version"] = depVersion
		*edges = append(*edges, e)
	}
}

func (d HelmChartDetector) detectValuesYaml(ctx *detector.Context, nodes *[]*model.CodeNode, edges *[]*model.CodeEdge) {
	fp := ctx.FilePath
	data := getHelmYAMLData(ctx)
	if data == nil {
		return
	}
	keys := make([]string, 0, len(data))
	for k := range data {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	for _, k := range keys {
		n := model.NewCodeNode("helm:"+fp+":value:"+k,
			model.NodeConfigKey, "helm-value:"+k)
		n.Module = ctx.ModuleName
		n.FilePath = fp
		n.Confidence = base.StructuredDetectorDefaultConfidence
		n.Properties["helm_value"] = true
		n.Properties["key"] = k
		*nodes = append(*nodes, n)
	}
}

func (d HelmChartDetector) detectTemplate(ctx *detector.Context, nodes *[]*model.CodeNode, edges *[]*model.CodeEdge) {
	fp := ctx.FilePath
	content := ctx.Content
	if content == "" {
		return
	}
	fileNodeID := "helm:" + fp + ":template"
	seenValues := map[string]bool{}
	seenIncludes := map[string]bool{}
	lines := strings.Split(content, "\n")
	for i, line := range lines {
		lineNo := i + 1
		for _, vm := range helmValuesRefRE.FindAllStringSubmatch(line, -1) {
			key := vm[1]
			if seenValues[key] {
				continue
			}
			seenValues[key] = true
			e := model.NewCodeEdge(fileNodeID+"->helm:values:"+key,
				model.EdgeReadsConfig, fileNodeID, "helm:values:"+key)
			e.Confidence = base.StructuredDetectorDefaultConfidence
			e.Properties["key"] = key
			e.Properties["line"] = lineNo
			*edges = append(*edges, e)
		}
		for _, im := range helmIncludeRE.FindAllStringSubmatch(line, -1) {
			helper := im[1]
			if seenIncludes[helper] {
				continue
			}
			seenIncludes[helper] = true
			e := model.NewCodeEdge(fileNodeID+"->helm:helper:"+helper,
				model.EdgeImports, fileNodeID, "helm:helper:"+helper)
			e.Confidence = base.StructuredDetectorDefaultConfidence
			e.Properties["helper"] = helper
			e.Properties["line"] = lineNo
			*edges = append(*edges, e)
		}
	}
}

func getHelmYAMLData(ctx *detector.Context) map[string]any {
	if ctx.ParsedData == nil {
		return nil
	}
	if base.GetString(ctx.ParsedData, "type") != "yaml" {
		return nil
	}
	data := base.GetMap(ctx.ParsedData, "data")
	if len(data) == 0 {
		return nil
	}
	return data
}
