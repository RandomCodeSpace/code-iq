package structured

import (
	"fmt"
	"path"
	"regexp"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// DockerComposeDetector mirrors Java DockerComposeDetector. Emits an
// INFRA_RESOURCE per service plus CONFIG_KEY children for ports / volumes /
// networks. Resolves depends_on → DEPENDS_ON and links → CONNECTS_TO edges
// between sibling services.
type DockerComposeDetector struct{}

func NewDockerComposeDetector() *DockerComposeDetector { return &DockerComposeDetector{} }

func (DockerComposeDetector) Name() string                        { return "docker_compose" }
func (DockerComposeDetector) SupportedLanguages() []string        { return []string{"yaml"} }
func (DockerComposeDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewDockerComposeDetector()) }

var composeFilenameRE = regexp.MustCompile(`(?i)^(docker-compose|compose).*\.(yml|yaml)$`)

func (d DockerComposeDetector) Detect(ctx *detector.Context) *detector.Result {
	if !d.isComposeFile(ctx) {
		return detector.EmptyResult()
	}
	if ctx.ParsedData == nil {
		return detector.EmptyResult()
	}
	data := base.GetMap(ctx.ParsedData, "data")
	if len(data) == 0 {
		return detector.EmptyResult()
	}
	services := base.GetMap(data, "services")
	if len(services) == 0 {
		return detector.EmptyResult()
	}
	fp := ctx.FilePath
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	svcNames := make([]string, 0, len(services))
	for n := range services {
		svcNames = append(svcNames, n)
	}
	sort.Strings(svcNames)
	serviceIDs := map[string]string{}
	for _, n := range svcNames {
		serviceIDs[n] = "compose:" + fp + ":service:" + n
	}

	for _, svcName := range svcNames {
		svcDef := base.AsMap(services[svcName])
		if len(svcDef) == 0 {
			continue
		}
		svcID := serviceIDs[svcName]
		props := map[string]any{}
		if image := base.GetString(svcDef, "image"); image != "" {
			props["image"] = image
		}
		if buildVal, ok := svcDef["build"]; ok {
			switch b := buildVal.(type) {
			case string:
				props["build_context"] = b
			case map[string]any:
				if ctx2 := base.GetString(b, "context"); ctx2 != "" {
					props["build_context"] = ctx2
				}
			}
		}
		sn := model.NewCodeNode(svcID, model.NodeInfraResource, svcName)
		sn.FQN = "compose:" + svcName
		sn.Module = ctx.ModuleName
		sn.FilePath = fp
		sn.Confidence = base.StructuredDetectorDefaultConfidence
		for k, v := range props {
			sn.Properties[k] = v
		}
		nodes = append(nodes, sn)

		// Ports
		for _, p := range base.GetList(svcDef, "ports") {
			portStr := fmt.Sprint(p)
			pn := model.NewCodeNode(
				"compose:"+fp+":service:"+svcName+":port:"+portStr,
				model.NodeConfigKey, svcName+" port "+portStr)
			pn.Module = ctx.ModuleName
			pn.FilePath = fp
			pn.Confidence = base.StructuredDetectorDefaultConfidence
			pn.Properties["port"] = portStr
			nodes = append(nodes, pn)
		}

		// depends_on
		depsRaw := svcDef["depends_on"]
		var deps []string
		switch t := depsRaw.(type) {
		case []any:
			for _, d := range t {
				deps = append(deps, fmt.Sprint(d))
			}
		case map[string]any:
			keys := make([]string, 0, len(t))
			for k := range t {
				keys = append(keys, k)
			}
			sort.Strings(keys)
			deps = keys
		}
		for _, dep := range deps {
			if tgt, ok := serviceIDs[dep]; ok {
				edges = append(edges, model.NewCodeEdge(
					svcID+"->"+tgt, model.EdgeDependsOn, svcID, tgt))
			}
		}

		// links
		for _, l := range base.GetList(svcDef, "links") {
			linkName := strings.Split(fmt.Sprint(l), ":")[0]
			if tgt, ok := serviceIDs[linkName]; ok {
				edges = append(edges, model.NewCodeEdge(
					svcID+"->"+tgt, model.EdgeConnectsTo, svcID, tgt))
			}
		}

		// volumes
		for _, v := range base.GetList(svcDef, "volumes") {
			var volStr string
			switch t := v.(type) {
			case map[string]any:
				if src, ok := t["source"]; ok && src != nil {
					volStr = fmt.Sprint(src)
				} else {
					volStr = fmt.Sprint(v)
				}
			default:
				volStr = fmt.Sprint(v)
			}
			vn := model.NewCodeNode(
				"compose:"+fp+":service:"+svcName+":volume:"+volStr,
				model.NodeConfigKey, svcName+" volume "+volStr)
			vn.Module = ctx.ModuleName
			vn.FilePath = fp
			vn.Confidence = base.StructuredDetectorDefaultConfidence
			vn.Properties["volume"] = volStr
			nodes = append(nodes, vn)
		}

		// networks
		switch nets := svcDef["networks"].(type) {
		case []any:
			for _, n := range nets {
				netStr := fmt.Sprint(n)
				nn := model.NewCodeNode(
					"compose:"+fp+":service:"+svcName+":network:"+netStr,
					model.NodeConfigKey, svcName+" network "+netStr)
				nn.Module = ctx.ModuleName
				nn.FilePath = fp
				nn.Confidence = base.StructuredDetectorDefaultConfidence
				nn.Properties["network"] = netStr
				nodes = append(nodes, nn)
			}
		case map[string]any:
			keys := make([]string, 0, len(nets))
			for k := range nets {
				keys = append(keys, k)
			}
			sort.Strings(keys)
			for _, k := range keys {
				nn := model.NewCodeNode(
					"compose:"+fp+":service:"+svcName+":network:"+k,
					model.NodeConfigKey, svcName+" network "+k)
				nn.Module = ctx.ModuleName
				nn.FilePath = fp
				nn.Confidence = base.StructuredDetectorDefaultConfidence
				nn.Properties["network"] = k
				nodes = append(nodes, nn)
			}
		}
	}
	return detector.ResultOf(nodes, edges)
}

func (d DockerComposeDetector) isComposeFile(ctx *detector.Context) bool {
	if ctx.FilePath == "" {
		return false
	}
	base2 := path.Base(ctx.FilePath)
	if composeFilenameRE.MatchString(base2) {
		return true
	}
	// Fallback: parsed data with a `services:` key at top level.
	if ctx.ParsedData != nil && base.GetString(ctx.ParsedData, "type") == "yaml" {
		data := base.GetMap(ctx.ParsedData, "data")
		if _, ok := data["services"]; ok {
			return true
		}
	}
	return false
}
