package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// JaxrsDetector mirrors Java JaxrsDetector. Detects JAX-RS @Path + HTTP-method
// annotations.
type JaxrsDetector struct{}

func NewJaxrsDetector() *JaxrsDetector { return &JaxrsDetector{} }

func (JaxrsDetector) Name() string                 { return "jaxrs" }
func (JaxrsDetector) SupportedLanguages() []string { return []string{"java"} }
func (JaxrsDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewJaxrsDetector()) }

var (
	jaxrsPathRE       = regexp.MustCompile(`@Path\s*\(\s*"([^"]*)"`)
	jaxrsHTTPMethodRE = regexp.MustCompile(`@(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\b`)
	jaxrsProducesRE   = regexp.MustCompile(`@Produces\s*\(\s*\{?\s*(?:MediaType\.\w+|"([^"]*)")`)
	jaxrsConsumesRE   = regexp.MustCompile(`@Consumes\s*\(\s*\{?\s*(?:MediaType\.\w+|"([^"]*)")`)
	jaxrsClassRE      = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
	jaxrsJavaMethodRE = regexp.MustCompile(
		`(?:public|protected|private)?\s*(?:static\s+)?(?:[\w<>\[\],\s]+)\s+(\w+)\s*\(`,
	)
)

func (d JaxrsDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "@Path") && !strings.Contains(text, "javax.ws.rs") && !strings.Contains(text, "jakarta.ws.rs") {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	var className string
	var classBasePath string
	for i, line := range lines {
		if cm := jaxrsClassRE.FindStringSubmatch(line); cm != nil {
			className = cm[1]
			for j := max0(i - 5); j < i; j++ {
				if pm := jaxrsPathRE.FindStringSubmatch(lines[j]); pm != nil {
					classBasePath = strings.TrimRight(pm[1], "/")
					break
				}
			}
			break
		}
	}
	if className == "" {
		return detector.EmptyResult()
	}
	classNodeID := ctx.FilePath + ":" + className

	for i, line := range lines {
		m := jaxrsHTTPMethodRE.FindStringSubmatch(line)
		if m == nil {
			continue
		}
		httpMethod := m[1]

		// Check if class-level (annotation right above `class ...`).
		isClassLevel := false
		for k := i + 1; k < min0(i+5, len(lines)); k++ {
			stripped := strings.TrimSpace(lines[k])
			if strings.HasPrefix(stripped, "@") || stripped == "" {
				continue
			}
			if strings.Contains(stripped, "class ") || strings.Contains(stripped, "interface ") {
				isClassLevel = true
			}
			break
		}
		if isClassLevel {
			continue
		}

		// Method-level @Path: scan a small window. When looking BACKWARD,
		// stop at the class/interface declaration line so we don't pick up
		// the class-level @Path above it.
		lowerBound := max0(i - 3)
		for k := i - 1; k >= lowerBound; k-- {
			stripped := strings.TrimSpace(lines[k])
			if strings.Contains(stripped, "class ") || strings.Contains(stripped, "interface ") {
				lowerBound = k + 1
				break
			}
		}
		var methodPath string
		for k := lowerBound; k < min0(i+4, len(lines)); k++ {
			if k == i {
				continue
			}
			if pm := jaxrsPathRE.FindStringSubmatch(lines[k]); pm != nil {
				methodPath = pm[1]
				break
			}
		}

		var fullPath string
		if methodPath != "" {
			fullPath = classBasePath + "/" + strings.TrimLeft(methodPath, "/")
		} else if classBasePath != "" {
			fullPath = classBasePath
		} else {
			fullPath = "/"
		}
		if !strings.HasPrefix(fullPath, "/") {
			fullPath = "/" + fullPath
		}

		var produces, consumes string
		for k := max0(i - 5); k < min0(i+5, len(lines)); k++ {
			if produces == "" {
				if pm := jaxrsProducesRE.FindStringSubmatch(lines[k]); pm != nil {
					produces = pm[1]
				}
			}
			if consumes == "" {
				if cm := jaxrsConsumesRE.FindStringSubmatch(lines[k]); cm != nil {
					consumes = cm[1]
				}
			}
		}

		var methodName string
		for k := i + 1; k < min0(i+5, len(lines)); k++ {
			if mm := jaxrsJavaMethodRE.FindStringSubmatch(lines[k]); mm != nil {
				methodName = mm[1]
				break
			}
		}
		methodLabel := "unknown"
		if methodName != "" {
			methodLabel = methodName
		}

		endpointLabel := httpMethod + " " + fullPath
		endpointID := ctx.FilePath + ":" + className + ":" + methodLabel + ":" + httpMethod + ":" + fullPath

		n := model.NewCodeNode(endpointID, model.NodeEndpoint, endpointLabel)
		if methodName != "" {
			n.FQN = className + "." + methodName
		} else {
			n.FQN = className
		}
		n.FilePath = ctx.FilePath
		n.LineStart = i + 1
		n.Source = "JaxrsDetector"
		n.Annotations = append(n.Annotations, "@"+httpMethod)
		n.Properties["http_method"] = httpMethod
		n.Properties["path"] = fullPath
		if produces != "" {
			n.Properties["produces"] = produces
		}
		if consumes != "" {
			n.Properties["consumes"] = consumes
		}
		nodes = append(nodes, n)
		edges = append(edges, model.NewCodeEdge(classNodeID+"->exposes->"+endpointID, model.EdgeExposes, classNodeID, endpointID))
	}

	return detector.ResultOf(nodes, edges)
}
