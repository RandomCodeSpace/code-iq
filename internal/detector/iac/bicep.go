package iac

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// BicepDetector detects Azure Bicep resources, params, and modules.
// Mirrors Java BicepDetector.
type BicepDetector struct{}

func NewBicepDetector() *BicepDetector { return &BicepDetector{} }

func (BicepDetector) Name() string                        { return "bicep" }
func (BicepDetector) SupportedLanguages() []string        { return []string{"bicep"} }
func (BicepDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewBicepDetector()) }

var (
	bicepResourceRE = regexp.MustCompile(`resource\s+(\w+)\s+'([^']+)'`)
	bicepParamRE    = regexp.MustCompile(`param\s+(\w+)\s+(\w+)`)
	bicepModuleRE   = regexp.MustCompile(`module\s+(\w+)\s+'([^']+)'`)
)

func (d BicepDetector) Detect(ctx *detector.Context) *detector.Result {
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
		if m := bicepResourceRE.FindStringSubmatch(line); len(m) >= 3 {
			name := m[1]
			typeStr := m[2]
			azureType := typeStr
			apiVersion := ""
			if at := strings.LastIndex(typeStr, "@"); at >= 0 {
				azureType = typeStr[:at]
				apiVersion = typeStr[at+1:]
			}
			kind := model.NodeInfraResource
			if strings.HasPrefix(azureType, "Microsoft.") {
				kind = model.NodeAzureResource
			}
			n := model.NewCodeNode(fp+":resource:"+name, kind, name+" ("+azureType+")")
			n.FQN = azureType
			n.FilePath = fp
			n.LineStart = i + 1
			n.Source = "BicepDetector"
			n.Properties["azure_type"] = azureType
			if apiVersion != "" {
				n.Properties["api_version"] = apiVersion
			}
			nodes = append(nodes, n)
		}

		if m := bicepParamRE.FindStringSubmatch(line); len(m) >= 3 {
			name := m[1]
			ptype := m[2]
			n := model.NewCodeNode(fp+":param:"+name, model.NodeConfigKey, "param "+name+": "+ptype)
			n.FilePath = fp
			n.LineStart = i + 1
			n.Source = "BicepDetector"
			n.Properties["param_type"] = ptype
			nodes = append(nodes, n)
		}

		if m := bicepModuleRE.FindStringSubmatch(line); len(m) >= 3 {
			name := m[1]
			modPath := m[2]
			n := model.NewCodeNode(fp+":module:"+name, model.NodeInfraResource, "module "+name+" ("+modPath+")")
			n.FilePath = fp
			n.LineStart = i + 1
			n.Source = "BicepDetector"
			n.Properties["module_path"] = modPath
			nodes = append(nodes, n)

			// Emit anchor nodes so the depends_on edge survives GraphBuilder's
			// phantom-drop filter. Without anchors, fp and modPath are free-form
			// strings that don't match any CodeNode.
			srcID := base.EnsureFileAnchor(ctx, "bicep", "BicepDetector", model.ConfidenceLexical, &nodes, seen)
			tgtID := base.EnsureExternalAnchor(modPath, "bicep:external", "BicepDetector", model.ConfidenceLexical, &nodes, seen)
			e := model.NewCodeEdge(srcID+":depends_on:"+tgtID, model.EdgeDependsOn, srcID, tgtID)
			e.Source = "BicepDetector"
			e.Properties["module_name"] = name
			edges = append(edges, e)
		}
	}

	return detector.ResultOf(nodes, edges)
}
