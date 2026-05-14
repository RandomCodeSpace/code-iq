package structured

import (
	"fmt"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// CloudFormationDetector mirrors Java CloudFormationDetector. Emits an
// INFRA_RESOURCE per logical CFN Resource, plus CONFIG_DEFINITION per
// Parameter / Output. DEPENDS_ON edges follow `Ref` and `Fn::GetAtt` chains
// inside resource bodies.
type CloudFormationDetector struct{}

func NewCloudFormationDetector() *CloudFormationDetector { return &CloudFormationDetector{} }

func (CloudFormationDetector) Name() string                        { return "cloudformation" }
func (CloudFormationDetector) SupportedLanguages() []string        { return []string{"yaml", "json"} }
func (CloudFormationDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewCloudFormationDetector()) }

func (d CloudFormationDetector) Detect(ctx *detector.Context) *detector.Result {
	data := cfnData(ctx)
	if data == nil {
		return detector.EmptyResult()
	}
	fp := ctx.FilePath
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	// Resources
	resources := base.GetMap(data, "Resources")
	resNames := mapKeysSorted(resources)
	for _, logicalID := range resNames {
		resource := base.AsMap(resources[logicalID])
		if len(resource) == 0 {
			continue
		}
		resType := base.GetStringOrDefault(resource, "Type", "unknown")
		nodeID := "cfn:" + fp + ":resource:" + logicalID

		n := model.NewCodeNode(nodeID, model.NodeInfraResource,
			logicalID+" ("+resType+")")
		n.FQN = "cfn:" + logicalID
		n.Module = ctx.ModuleName
		n.FilePath = fp
		n.Confidence = base.StructuredDetectorDefaultConfidence
		n.Properties["logical_id"] = logicalID
		n.Properties["resource_type"] = resType
		nodes = append(nodes, n)

		refs := map[string]bool{}
		collectCFNRefs(resource, refs)
		delete(refs, logicalID)
		refList := make([]string, 0, len(refs))
		for k := range refs {
			refList = append(refList, k)
		}
		sort.Strings(refList)
		for _, ref := range refList {
			e := model.NewCodeEdge(
				nodeID+"->cfn:"+fp+":resource:"+ref,
				model.EdgeDependsOn, nodeID, "cfn:"+fp+":resource:"+ref)
			e.Confidence = base.StructuredDetectorDefaultConfidence
			e.Properties["ref_type"] = "Ref/GetAtt"
			edges = append(edges, e)
		}
	}

	// Parameters
	parameters := base.GetMap(data, "Parameters")
	paramNames := mapKeysSorted(parameters)
	for _, name := range paramNames {
		def := base.AsMap(parameters[name])
		if len(def) == 0 {
			continue
		}
		props := map[string]any{
			"param_type": base.GetStringOrDefault(def, "Type", "String"),
			"cfn_type":   "parameter",
		}
		if dv, ok := def["Default"]; ok && dv != nil {
			props["default"] = fmt.Sprint(dv)
		}
		if desc := base.GetString(def, "Description"); desc != "" {
			props["description"] = desc
		}
		pn := model.NewCodeNode("cfn:"+fp+":parameter:"+name,
			model.NodeConfigDefinition, "param:"+name)
		pn.FQN = "cfn:param:" + name
		pn.Module = ctx.ModuleName
		pn.FilePath = fp
		pn.Confidence = base.StructuredDetectorDefaultConfidence
		for k, v := range props {
			pn.Properties[k] = v
		}
		nodes = append(nodes, pn)
	}

	// Outputs
	outputs := base.GetMap(data, "Outputs")
	outNames := mapKeysSorted(outputs)
	for _, name := range outNames {
		def := base.AsMap(outputs[name])
		if len(def) == 0 {
			continue
		}
		props := map[string]any{"cfn_type": "output"}
		if desc := base.GetString(def, "Description"); desc != "" {
			props["description"] = desc
		}
		export := base.GetMap(def, "Export")
		if exportName := base.GetString(export, "Name"); exportName != "" {
			props["export_name"] = exportName
		}
		on := model.NewCodeNode("cfn:"+fp+":output:"+name,
			model.NodeConfigDefinition, "output:"+name)
		on.FQN = "cfn:output:" + name
		on.Module = ctx.ModuleName
		on.FilePath = fp
		on.Confidence = base.StructuredDetectorDefaultConfidence
		for k, v := range props {
			on.Properties[k] = v
		}
		nodes = append(nodes, on)
	}
	return detector.ResultOf(nodes, edges)
}

func cfnData(ctx *detector.Context) map[string]any {
	if ctx.ParsedData == nil {
		return nil
	}
	ptype := base.GetString(ctx.ParsedData, "type")
	if ptype != "yaml" && ptype != "json" {
		return nil
	}
	data := base.GetMap(ctx.ParsedData, "data")
	if len(data) == 0 {
		return nil
	}
	if isCFNTemplate(data) {
		return data
	}
	return nil
}

func isCFNTemplate(data map[string]any) bool {
	if _, ok := data["AWSTemplateFormatVersion"]; ok {
		return true
	}
	resources := base.GetMap(data, "Resources")
	for _, v := range resources {
		resource := base.AsMap(v)
		rtype := base.GetString(resource, "Type")
		if strings.HasPrefix(rtype, "AWS::") {
			return true
		}
	}
	return false
}

func collectCFNRefs(value any, refs map[string]bool) {
	switch v := value.(type) {
	case map[string]any:
		if r, ok := v["Ref"]; ok {
			if s, ok := r.(string); ok {
				refs[s] = true
			}
		}
		if getAtt, ok := v["Fn::GetAtt"]; ok {
			switch g := getAtt.(type) {
			case []any:
				if len(g) > 0 {
					refs[fmt.Sprint(g[0])] = true
				}
			case string:
				if i := strings.IndexByte(g, '.'); i > 0 {
					refs[g[:i]] = true
				}
			}
		}
		for _, vv := range v {
			collectCFNRefs(vv, refs)
		}
	case []any:
		for _, item := range v {
			collectCFNRefs(item, refs)
		}
	}
}
