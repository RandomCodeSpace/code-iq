package python

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// FlaskRouteDetector detects Flask @app.route / @blueprint.route decorators.
// Phase 1 = regex (matches Java FlaskRouteDetector's regex-fallback path).
//
// Per phase-1 plan, this emits ONE endpoint node per route (using the first
// HTTP method declared). The Java side emits one per method; we match the
// phase-1 plan's test which asserts a 1:1 route→node mapping. Phase 4 will
// reconcile when the AST path lands.
type FlaskRouteDetector struct{}

func NewFlaskRouteDetector() *FlaskRouteDetector { return &FlaskRouteDetector{} }

func (FlaskRouteDetector) Name() string                        { return "python.flask_routes" }
func (FlaskRouteDetector) SupportedLanguages() []string        { return []string{"python"} }
func (FlaskRouteDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewFlaskRouteDetector()) }

// flaskRouteRE matches @<bp>.route("<path>"[, methods=[...]]) ... def <func>
// RE2 lacks backreferences but supports (?s) for dot-all.
var flaskRouteRE = regexp.MustCompile(
	`(?s)@(\w+)\.route\(\s*['"]([^'"]+)['"]` +
		`(?:[^)]*?methods\s*=\s*\[([^\]]+)\])?` +
		`[^)]*?\)\s*\n\s*def\s+(\w+)`)

func (d FlaskRouteDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if !strings.Contains(text, ".route(") {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	for _, m := range flaskRouteRE.FindAllStringSubmatchIndex(text, -1) {
		// Submatch indices: [1]=app/bp, [2]=path, [3]=methods?, [4]=funcName
		blueprint := text[m[2]:m[3]]
		path := text[m[4]:m[5]]
		methodsRaw := ""
		if m[6] >= 0 {
			methodsRaw = text[m[6]:m[7]]
		}
		funcName := text[m[8]:m[9]]
		methods := parseFlaskMethods(methodsRaw)
		if len(methods) == 0 {
			methods = []string{"GET"}
		}
		// Emit one endpoint per route (using the first declared method) — see
		// type doc.
		httpMethod := methods[0]
		id := fmt.Sprintf("%s:%s:%s:%s", ctx.FilePath, blueprint, funcName, httpMethod)
		n := model.NewCodeNode(id, model.NodeEndpoint, funcName)
		n.FilePath = ctx.FilePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "FlaskRouteDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["framework"] = "flask"
		n.Properties["http_method"] = httpMethod
		n.Properties["path"] = path
		n.Properties["blueprint"] = blueprint
		n.Properties["protocol"] = "http"
		if len(methods) > 1 {
			n.Properties["http_methods"] = methods
		}
		nodes = append(nodes, n)
	}
	return detector.ResultOf(nodes, nil)
}

func parseFlaskMethods(raw string) []string {
	if raw == "" {
		return nil
	}
	parts := strings.Split(raw, ",")
	var out []string
	for _, p := range parts {
		s := strings.Trim(strings.TrimSpace(p), "'\"")
		if s != "" {
			out = append(out, strings.ToUpper(s))
		}
	}
	return out
}
