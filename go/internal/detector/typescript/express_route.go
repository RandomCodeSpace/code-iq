// Package typescript ports the Java TypeScript detectors.
// Per phase-1 plan, we ship regex-fallback paths only — AST refinement
// (tree-sitter typescript grammar) is deferred to phase 5.
package typescript

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// ExpressRouteDetector ports
// io.github.randomcodespace.iq.detector.typescript.ExpressRouteDetector.
// Detects calls like `app.get("/path", handler)` or `router.post(...)`.
type ExpressRouteDetector struct{}

func NewExpressRouteDetector() *ExpressRouteDetector { return &ExpressRouteDetector{} }

func (ExpressRouteDetector) Name() string                 { return "typescript.express_routes" }
func (ExpressRouteDetector) SupportedLanguages() []string { return []string{"typescript", "javascript"} }
func (ExpressRouteDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewExpressRouteDetector()) }

var expressRouteRE = regexp.MustCompile(
	`(\w+)\.(get|post|put|delete|patch|options|head|all)\(\s*['"` + "`" + `]([^'"` + "`" + `]+)['"` + "`" + `]`)

func (d ExpressRouteDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	for _, m := range expressRouteRE.FindAllStringSubmatchIndex(text, -1) {
		router := text[m[2]:m[3]]
		method := strings.ToUpper(text[m[4]:m[5]])
		path := text[m[6]:m[7]]
		line := base.FindLineNumber(text, m[0])

		moduleName := ctx.ModuleName
		nodeID := fmt.Sprintf("endpoint:%s:%s:%s", moduleName, method, path)
		n := model.NewCodeNode(nodeID, model.NodeEndpoint, method+" "+path)
		n.FQN = ctx.FilePath + "::" + method + ":" + path
		n.Module = moduleName
		n.FilePath = ctx.FilePath
		n.LineStart = line
		n.Source = "ExpressRouteDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["protocol"] = "REST"
		n.Properties["http_method"] = method
		n.Properties["path_pattern"] = path
		n.Properties["framework"] = "express"
		n.Properties["router"] = router
		nodes = append(nodes, n)
	}
	return detector.ResultOf(nodes, nil)
}
