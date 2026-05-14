package python

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// FastAPIRouteDetector ports
// io.github.randomcodespace.iq.detector.python.FastAPIRouteDetector.
type FastAPIRouteDetector struct{}

func NewFastAPIRouteDetector() *FastAPIRouteDetector { return &FastAPIRouteDetector{} }

func (FastAPIRouteDetector) Name() string                        { return "python.fastapi_routes" }
func (FastAPIRouteDetector) SupportedLanguages() []string        { return []string{"python"} }
func (FastAPIRouteDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewFastAPIRouteDetector()) }

var (
	fastapiRouteRE = regexp.MustCompile(
		`(?s)@(\w+)\.(get|post|put|delete|patch|options|head)\(\s*['"]([^'"]+)['"].*?\)\s*\n(?:\s*async\s+)?def\s+(\w+)`)
	fastapiRouterPrefixRE = regexp.MustCompile(
		`(?s)(\w+)\s*=\s*APIRouter\(.*?prefix\s*=\s*['"]([^'"]+)['"]`)
)

func (d FastAPIRouteDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName

	prefixes := make(map[string]string)
	for _, m := range fastapiRouterPrefixRE.FindAllStringSubmatch(text, -1) {
		prefixes[m[1]] = m[2]
	}

	for _, m := range fastapiRouteRE.FindAllStringSubmatchIndex(text, -1) {
		routerName := text[m[2]:m[3]]
		method := strings.ToUpper(text[m[4]:m[5]])
		path := text[m[6]:m[7]]
		funcName := text[m[8]:m[9]]
		prefix := prefixes[routerName]
		fullPath := prefix + path
		line := base.FindLineNumber(text, m[0])

		id := "endpoint:" + moduleName + ":" + method + ":" + fullPath
		n := model.NewCodeNode(id, model.NodeEndpoint, method+" "+fullPath)
		n.FQN = filePath + "::" + funcName
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "FastAPIRouteDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["protocol"] = "REST"
		n.Properties["http_method"] = method
		n.Properties["path_pattern"] = fullPath
		n.Properties["framework"] = "fastapi"
		n.Properties["router"] = routerName
		nodes = append(nodes, n)
	}
	return detector.ResultOf(nodes, nil)
}
