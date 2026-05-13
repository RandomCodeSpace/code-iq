package scala

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/detector/jvm/jvmhelpers"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// ScalaStructuresDetector mirrors Java ScalaStructuresDetector regex tier.
type ScalaStructuresDetector struct{}

func NewScalaStructuresDetector() *ScalaStructuresDetector { return &ScalaStructuresDetector{} }

func (ScalaStructuresDetector) Name() string                 { return "scala_structures" }
func (ScalaStructuresDetector) SupportedLanguages() []string { return []string{"scala"} }
func (ScalaStructuresDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewScalaStructuresDetector()) }

var (
	scalaImportRE    = regexp.MustCompile(`(?m)^\s*import\s+([\w.]+)`)
	scalaClassRE     = regexp.MustCompile(`(?m)^\s*(?:case\s+)?class\s+(\w+)(?:\s+extends\s+(\w+))?(?:\s+with\s+([\w\s,]+))?`)
	scalaTraitRE     = regexp.MustCompile(`(?m)^\s*trait\s+(\w+)`)
	scalaObjectRE    = regexp.MustCompile(`(?m)^\s*object\s+(\w+)`)
	scalaDefRE       = regexp.MustCompile(`(?m)^\s*def\s+(\w+)\s*[\[(]`)
)

func (d ScalaStructuresDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}

	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	fp := ctx.FilePath

	for _, m := range scalaImportRE.FindAllStringSubmatch(text, -1) {
		edges = jvmhelpers.AddImportEdge(fp, m[1], edges)
	}

	for _, m := range scalaClassRE.FindAllStringSubmatchIndex(text, -1) {
		className := text[m[2]:m[3]]
		var baseClass, traitsStr string
		if m[4] >= 0 {
			baseClass = text[m[4]:m[5]]
		}
		if m[6] >= 0 {
			traitsStr = text[m[6]:m[7]]
		}
		nodeID := fp + ":" + className
		nodes = append(nodes, jvmhelpers.CreateStructureNode(fp, className, model.NodeClass, base.FindLineNumber(text, m[0])))
		if baseClass != "" {
			edges = jvmhelpers.AddExtendsEdge(nodeID, baseClass, model.NodeClass, edges)
		}
		if traitsStr != "" {
			for _, t := range strings.Split(traitsStr, ",") {
				t = strings.TrimSpace(t)
				if t != "" {
					edges = jvmhelpers.AddImplementsEdge(nodeID, t, edges)
				}
			}
		}
	}

	for _, m := range scalaTraitRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := jvmhelpers.CreateStructureNode(fp, name, model.NodeInterface, base.FindLineNumber(text, m[0]))
		n.Properties["type"] = "trait"
		nodes = append(nodes, n)
	}

	for _, m := range scalaObjectRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := jvmhelpers.CreateStructureNode(fp, name, model.NodeClass, base.FindLineNumber(text, m[0]))
		n.Properties["type"] = "object"
		nodes = append(nodes, n)
	}

	for _, m := range scalaDefRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		nodes = append(nodes, jvmhelpers.CreateStructureNode(fp, name, model.NodeMethod, base.FindLineNumber(text, m[0])))
	}

	return detector.ResultOf(nodes, edges)
}
