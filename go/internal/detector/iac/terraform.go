// Package iac holds Infrastructure-as-Code detectors (Terraform, Bicep,
// Dockerfile, ...).
package iac

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// TerraformDetector detects Terraform resources, data sources, modules,
// variables, outputs, and providers. Mirrors Java TerraformDetector.
type TerraformDetector struct{}

func NewTerraformDetector() *TerraformDetector { return &TerraformDetector{} }

func (TerraformDetector) Name() string                        { return "terraform" }
func (TerraformDetector) SupportedLanguages() []string        { return []string{"terraform"} }
func (TerraformDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewTerraformDetector()) }

var (
	tfResourceRE = regexp.MustCompile(`resource\s+"([^"]+)"\s+"([^"]+)"`)
	tfDataRE     = regexp.MustCompile(`data\s+"([^"]+)"\s+"([^"]+)"`)
	tfModuleRE   = regexp.MustCompile(`module\s+"([^"]+)"`)
	tfVariableRE = regexp.MustCompile(`variable\s+"([^"]+)"`)
	tfOutputRE   = regexp.MustCompile(`output\s+"([^"]+)"`)
	tfProviderRE = regexp.MustCompile(`provider\s+"([^"]+)"`)
	tfSourceRE   = regexp.MustCompile(`source\s*=\s*"([^"]+)"`)
)

func tfExtractProvider(resourceType string) string {
	if i := strings.Index(resourceType, "_"); i > 0 {
		return resourceType[:i]
	}
	return ""
}

func tfFindSourceInBlock(text string, blockStart int) string {
	brace := strings.IndexByte(text[blockStart:], '{')
	if brace == -1 {
		return ""
	}
	off := blockStart + brace
	end := off + 500
	if end > len(text) {
		end = len(text)
	}
	snippet := text[off:end]
	if m := tfSourceRE.FindStringSubmatch(snippet); len(m) >= 2 {
		return m[1]
	}
	return ""
}

func (d TerraformDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	fp := ctx.FilePath

	// Resources
	for _, m := range tfResourceRE.FindAllStringSubmatchIndex(text, -1) {
		rtype := text[m[2]:m[3]]
		rname := text[m[4]:m[5]]
		provider := tfExtractProvider(rtype)
		n := model.NewCodeNode("tf:resource:"+rtype+":"+rname, model.NodeInfraResource, rtype+"."+rname)
		n.FQN = rtype + "." + rname
		n.FilePath = fp
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "TerraformDetector"
		n.Properties["resource_type"] = rtype
		if provider != "" {
			n.Properties["provider"] = provider
		}
		nodes = append(nodes, n)
	}

	// Data sources
	for _, m := range tfDataRE.FindAllStringSubmatchIndex(text, -1) {
		dtype := text[m[2]:m[3]]
		dname := text[m[4]:m[5]]
		provider := tfExtractProvider(dtype)
		n := model.NewCodeNode("tf:data:"+dtype+":"+dname, model.NodeInfraResource, "data."+dtype+"."+dname)
		n.FQN = "data." + dtype + "." + dname
		n.FilePath = fp
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "TerraformDetector"
		n.Properties["resource_type"] = dtype
		n.Properties["data_source"] = true
		if provider != "" {
			n.Properties["provider"] = provider
		}
		nodes = append(nodes, n)
	}

	// Modules
	for _, m := range tfModuleRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		source := tfFindSourceInBlock(text, m[0])
		modID := "tf:module:" + name
		n := model.NewCodeNode(modID, model.NodeModule, "module."+name)
		n.FQN = "module." + name
		n.FilePath = fp
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "TerraformDetector"
		if source != "" {
			n.Properties["source"] = source
			e := model.NewCodeEdge(
				"tf:module:"+name+":depends_on:"+source,
				model.EdgeDependsOn, modID, source,
			)
			e.Source = "TerraformDetector"
			e.Properties["module_source"] = source
			edges = append(edges, e)
		}
		nodes = append(nodes, n)
	}

	// Variables
	for _, m := range tfVariableRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := model.NewCodeNode("tf:var:"+name, model.NodeConfigDefinition, "var."+name)
		n.FQN = "var." + name
		n.FilePath = fp
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "TerraformDetector"
		n.Properties["config_type"] = "variable"
		nodes = append(nodes, n)
	}

	// Outputs
	for _, m := range tfOutputRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := model.NewCodeNode("tf:output:"+name, model.NodeConfigDefinition, "output."+name)
		n.FQN = "output." + name
		n.FilePath = fp
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "TerraformDetector"
		n.Properties["config_type"] = "output"
		nodes = append(nodes, n)
	}

	// Providers
	for _, m := range tfProviderRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := model.NewCodeNode("tf:provider:"+name, model.NodeInfraResource, "provider."+name)
		n.FQN = "provider." + name
		n.FilePath = fp
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "TerraformDetector"
		n.Properties["resource_type"] = "provider"
		n.Properties["provider"] = name
		nodes = append(nodes, n)
	}

	return detector.ResultOf(nodes, edges)
}
