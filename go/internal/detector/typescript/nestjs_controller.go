package typescript

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// NestJSControllerDetector ports
// io.github.randomcodespace.iq.detector.typescript.NestJSControllerDetector.
// Detects @Controller classes and @Get/@Post/@etc. route methods, plus emits
// EXPOSES edges from the controller class to each route. Guard: requires
// `from '@nestjs/'` import to avoid generic decorator false-positives.
type NestJSControllerDetector struct{}

func NewNestJSControllerDetector() *NestJSControllerDetector { return &NestJSControllerDetector{} }

func (NestJSControllerDetector) Name() string { return "typescript.nestjs_controllers" }
func (NestJSControllerDetector) SupportedLanguages() []string {
	return []string{"typescript", "javascript"}
}
func (NestJSControllerDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewNestJSControllerDetector()) }

var (
	nestjsImportRE     = regexp.MustCompile(`from\s+['"]@nestjs/`)
	// RE2 lacks possessive quantifiers; replace `*+` with `*` (RE2 is
	// already linear-time so the original Java motivation for *+ doesn't
	// apply).
	nestjsControllerRE = regexp.MustCompile(
		`@Controller\(\s*['"` + "`" + `]?([^'"` + "`" + `)\s]*)['"` + "`" + `]?\s*\)` +
			`(?:\s*@\w+\([^)]{0,200}\))*\s*\n\s*(?:export\s+)?class\s+(\w+)`)
	nestjsRouteRE = regexp.MustCompile(
		`@(Get|Post|Put|Delete|Patch|Options|Head)\(\s*['"` + "`" + `]?([^'"` + "`" + `)\s]*)['"` + "`" + `]?\s*\)` +
			`(?:\s*@\w+\([^)]{0,200}\))*\s*\n\s*(?:async\s+)?(\w+)`)
)

// repeatedSlashesRE collapses //+ → /
var repeatedSlashesRE = regexp.MustCompile(`/+`)

func (d NestJSControllerDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if !nestjsImportRE.MatchString(text) {
		return detector.EmptyResult()
	}

	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName

	type ctrlInfo struct {
		line int
		name string
		base string
	}
	var ctrls []ctrlInfo

	for _, m := range nestjsControllerRE.FindAllStringSubmatchIndex(text, -1) {
		basePath := ""
		if m[2] >= 0 {
			basePath = text[m[2]:m[3]]
		}
		className := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])
		ctrls = append(ctrls, ctrlInfo{line: line, name: className, base: basePath})

		classID := "class:" + filePath + "::" + className
		n := model.NewCodeNode(classID, model.NodeClass, className)
		n.FQN = filePath + "::" + className
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "NestJSControllerDetector"
		n.Confidence = model.ConfidenceLexical
		n.Annotations = append(n.Annotations, "@Controller")
		n.Properties["framework"] = "nestjs"
		n.Properties["stereotype"] = "controller"
		nodes = append(nodes, n)
	}

	for _, m := range nestjsRouteRE.FindAllStringSubmatchIndex(text, -1) {
		routeLine := base.FindLineNumber(text, m[0])
		// Find enclosing controller (latest controller declared before route line).
		currentClass := ""
		basePath := ""
		for _, c := range ctrls {
			if c.line <= routeLine {
				currentClass = c.name
				basePath = c.base
			}
		}

		method := strings.ToUpper(text[m[2]:m[3]])
		path := ""
		if m[4] >= 0 {
			path = text[m[4]:m[5]]
		}
		funcName := text[m[6]:m[7]]

		fullPath := "/" + basePath + "/" + path
		fullPath = repeatedSlashesRE.ReplaceAllString(fullPath, "/")
		if len(fullPath) > 1 && strings.HasSuffix(fullPath, "/") {
			fullPath = fullPath[:len(fullPath)-1]
		}
		if fullPath == "" {
			fullPath = "/"
		}

		nodeID := "endpoint:" + moduleName + ":" + method + ":" + fullPath
		n := model.NewCodeNode(nodeID, model.NodeEndpoint, method+" "+fullPath)
		n.FQN = filePath + "::" + funcName
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = routeLine
		n.Source = "NestJSControllerDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["protocol"] = "REST"
		n.Properties["http_method"] = method
		n.Properties["path_pattern"] = fullPath
		n.Properties["framework"] = "nestjs"
		nodes = append(nodes, n)

		if currentClass != "" {
			classID := "class:" + filePath + "::" + currentClass
			e := model.NewCodeEdge(classID+"->exposes->"+nodeID, model.EdgeExposes, classID, nodeID)
			e.Source = "NestJSControllerDetector"
			e.Confidence = model.ConfidenceLexical
			edges = append(edges, e)
		}
	}

	return detector.ResultOf(nodes, edges)
}
