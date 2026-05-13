package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// PublicApiDetector mirrors Java PublicApiDetector regex tier.
type PublicApiDetector struct{}

func NewPublicApiDetector() *PublicApiDetector { return &PublicApiDetector{} }

func (PublicApiDetector) Name() string                 { return "java.public_api" }
func (PublicApiDetector) SupportedLanguages() []string { return []string{"java"} }
func (PublicApiDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewPublicApiDetector()) }

var (
	paClassRE     = regexp.MustCompile(`(?:public\s+)?(?:abstract\s+)?class\s+(\w+)`)
	paInterfaceRE = regexp.MustCompile(`(?:public\s+)?interface\s+(\w+)`)
	paMethodRE    = regexp.MustCompile(
		`(public|protected)\s+(?:static\s+)?(?:abstract\s+)?([\w<>\[\],?\s]+)\s+(\w+)\s*\(([^)]*)\)`,
	)
)

var paSkipMethods = map[string]bool{
	"toString": true, "hashCode": true, "equals": true, "clone": true, "finalize": true,
}

func (d PublicApiDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	var className string
	for _, line := range lines {
		if im := paInterfaceRE.FindStringSubmatch(line); im != nil {
			className = im[1]
			break
		}
		if cm := paClassRE.FindStringSubmatch(line); cm != nil {
			className = cm[1]
			break
		}
	}
	if className == "" {
		return detector.EmptyResult()
	}
	classNodeID := ctx.FilePath + ":" + className

	for i, line := range lines {
		m := paMethodRE.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		visibility := m[1]
		returnType := strings.TrimSpace(m[2])
		methodName := m[3]
		paramsStr := strings.TrimSpace(m[4])

		if paSkipMethods[methodName] {
			continue
		}

		var paramTypes []string
		if paramsStr != "" {
			for _, param := range strings.Split(paramsStr, ",") {
				trimmed := strings.TrimSpace(param)
				lastSpace := strings.LastIndex(trimmed, " ")
				if lastSpace > 0 {
					paramTypes = append(paramTypes, strings.TrimSpace(trimmed[:lastSpace]))
				}
			}
		}

		if paIsTrivialAccessor(methodName, len(paramTypes)) {
			continue
		}

		isStatic := strings.Contains(line, "static ")
		isAbstract := strings.Contains(line, "abstract ")

		paramSig := strings.Join(paramTypes, ",")
		methodID := ctx.FilePath + ":" + className + ":" + methodName + "(" + paramSig + ")"

		n := model.NewCodeNode(methodID, model.NodeMethod, className+"."+methodName)
		n.FQN = className + "." + methodName + "(" + paramSig + ")"
		n.FilePath = ctx.FilePath
		n.LineStart = i + 1
		n.Source = "PublicApiDetector"
		n.Properties["visibility"] = visibility
		n.Properties["return_type"] = returnType
		n.Properties["parameters"] = paramTypes
		n.Properties["is_static"] = isStatic
		n.Properties["is_abstract"] = isAbstract
		nodes = append(nodes, n)

		edges = append(edges, model.NewCodeEdge(classNodeID+"->defines->"+methodID, model.EdgeDefines, classNodeID, methodID))
	}

	return detector.ResultOf(nodes, edges)
}

func paIsTrivialAccessor(name string, paramCount int) bool {
	if paramCount > 1 {
		return false
	}
	return strings.HasPrefix(name, "get") || strings.HasPrefix(name, "set") || strings.HasPrefix(name, "is")
}
