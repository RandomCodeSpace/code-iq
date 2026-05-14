package kotlin

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/detector/jvm/jvmhelpers"
	"github.com/randomcodespace/codeiq/internal/model"
)

// KotlinStructuresDetector detects Kotlin classes/interfaces/objects/funs +
// imports. Mirrors Java KotlinStructuresDetector (regex tier).
type KotlinStructuresDetector struct{}

func NewKotlinStructuresDetector() *KotlinStructuresDetector { return &KotlinStructuresDetector{} }

func (KotlinStructuresDetector) Name() string                 { return "kotlin_structures" }
func (KotlinStructuresDetector) SupportedLanguages() []string { return []string{"kotlin"} }
func (KotlinStructuresDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewKotlinStructuresDetector()) }

// Patterns mirror Java KotlinStructuresDetector. Multi-line / Pattern.MULTILINE
// in Java → `(?m)` prefix in Go.
var (
	kotlinImportRE = regexp.MustCompile(`(?m)^\s*import\s+([\w.]+)`)
	// Class: optionally preceded by modifiers (data/open/abstract/sealed/enum/annotation/value/inline),
	// then `class Name`, optional ctor args `(...)`, optional `: SuperType[, ...]`.
	kotlinClassRE = regexp.MustCompile(
		`(?m)^\s*(?:(?:data|open|abstract|sealed|enum|annotation|value|inline)\s+)*class\s+(\w+)(?:\s*(?:\(.*?\))?\s*:\s*([\w\s,.<>]+))?`,
	)
	kotlinInterfaceRE = regexp.MustCompile(`(?m)^\s*interface\s+(\w+)`)
	kotlinFunRE       = regexp.MustCompile(
		`(?m)^\s*(?:(?:override|inline|private|protected|internal|public)\s+)*(?:fun|suspend\s+fun)\s+(\w+)\s*\(`,
	)
	kotlinObjectRE = regexp.MustCompile(`(?m)^\s*object\s+(\w+)`)
)

func (d KotlinStructuresDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}

	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	fp := ctx.FilePath

	// Imports
	for _, m := range kotlinImportRE.FindAllStringSubmatch(text, -1) {
		edges = jvmhelpers.AddImportEdge(fp, m[1], edges)
	}

	// Classes (with optional supertype list after `:`).
	for _, m := range kotlinClassRE.FindAllStringSubmatchIndex(text, -1) {
		className := text[m[2]:m[3]]
		var supertypesStr string
		if m[4] >= 0 {
			supertypesStr = text[m[4]:m[5]]
		}
		nodeID := fp + ":" + className
		nodes = append(nodes, jvmhelpers.CreateStructureNode(fp, className, model.NodeClass, base.FindLineNumber(text, m[0])))
		if supertypesStr != "" {
			for _, st := range strings.Split(supertypesStr, ",") {
				st = strings.TrimSpace(st)
				// Drop generic params `<...>` and ctor args `(...)`.
				if idx := strings.Index(st, "("); idx >= 0 {
					st = st[:idx]
				}
				if idx := strings.Index(st, "<"); idx >= 0 {
					st = st[:idx]
				}
				st = strings.TrimSpace(st)
				if st != "" {
					edges = jvmhelpers.AddExtendsEdge(nodeID, st, model.NodeClass, edges)
				}
			}
		}
	}

	// Interfaces
	for _, m := range kotlinInterfaceRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		nodes = append(nodes, jvmhelpers.CreateStructureNode(fp, name, model.NodeInterface, base.FindLineNumber(text, m[0])))
	}

	// Objects
	for _, m := range kotlinObjectRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := jvmhelpers.CreateStructureNode(fp, name, model.NodeClass, base.FindLineNumber(text, m[0]))
		n.Properties["type"] = "object"
		nodes = append(nodes, n)
	}

	// Funs
	for _, m := range kotlinFunRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		nodes = append(nodes, jvmhelpers.CreateStructureNode(fp, name, model.NodeMethod, base.FindLineNumber(text, m[0])))
	}

	return detector.ResultOf(nodes, edges)
}
