package golang

import (
	"regexp"
	"strconv"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// WebDetector detects Go web endpoints (Gin, Echo, Chi, gorilla/mux, net/http)
// and middleware. Mirrors Java GoWebDetector.
type WebDetector struct{}

func NewWebDetector() *WebDetector { return &WebDetector{} }

func (WebDetector) Name() string                        { return "go_web" }
func (WebDetector) SupportedLanguages() []string        { return []string{"go"} }
func (WebDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewWebDetector()) }

var (
	goUpperRouteRE        = regexp.MustCompile(`(?m)\.(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\s*\(\s*"([^"]*)"`)
	goLowerRouteRE        = regexp.MustCompile(`(?m)\.(Get|Post|Put|Delete|Patch|Head|Options)\s*\(\s*"([^"]*)"`)
	goHandleFuncMethodRE  = regexp.MustCompile(`\.HandleFunc\s*\(\s*"([^"]*)"[^\n]*?\.Methods\s*\(\s*"([A-Z]+)"`)
	goHandleFuncNoMethRE  = regexp.MustCompile(`(?m)\.HandleFunc\s*\(\s*"([^"]*)"`)
	goHttpHandleRE        = regexp.MustCompile(`(?m)http\.(?:HandleFunc|Handle)\s*\(\s*"([^"]*)"`)
	goGinFrameworkRE      = regexp.MustCompile(`gin\.(?:Default|New)\s*\(`)
	goEchoFrameworkRE     = regexp.MustCompile(`echo\.New\s*\(`)
	goChiFrameworkRE      = regexp.MustCompile(`chi\.NewRouter\s*\(`)
	goMuxFrameworkRE      = regexp.MustCompile(`mux\.NewRouter\s*\(`)
	goUseMiddlewareRE     = regexp.MustCompile(`\.Use\s*\(\s*(\w+)`)
)

func detectGoWebFramework(text string) string {
	switch {
	case goGinFrameworkRE.MatchString(text):
		return "gin"
	case goEchoFrameworkRE.MatchString(text):
		return "echo"
	case goChiFrameworkRE.MatchString(text):
		return "chi"
	case goMuxFrameworkRE.MatchString(text):
		return "mux"
	default:
		return "net_http"
	}
}

func (d WebDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	filePath := ctx.FilePath
	framework := detectGoWebFramework(text)

	// Upper-case routes (.GET, .POST etc) — Gin / Echo
	for _, m := range goUpperRouteRE.FindAllStringSubmatchIndex(text, -1) {
		method := text[m[2]:m[3]]
		path := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])
		nodes = append(nodes, mkGoEndpoint(filePath, method, path, line, framework))
	}

	// Lower-case routes (.Get, .Post etc) — Chi
	for _, m := range goLowerRouteRE.FindAllStringSubmatchIndex(text, -1) {
		method := strings.ToUpper(text[m[2]:m[3]])
		path := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])
		nodes = append(nodes, mkGoEndpoint(filePath, method, path, line, "chi"))
	}

	// gorilla/mux HandleFunc + .Methods(...)
	handleFuncWithMethodStarts := map[int]bool{}
	for _, m := range goHandleFuncMethodRE.FindAllStringSubmatchIndex(text, -1) {
		path := text[m[2]:m[3]]
		method := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])
		nodes = append(nodes, mkGoEndpoint(filePath, method, path, line, "mux"))
		handleFuncWithMethodStarts[m[0]] = true
	}

	// gorilla/mux HandleFunc without .Methods() — only when framework is mux
	if framework == "mux" {
		for _, m := range goHandleFuncNoMethRE.FindAllStringSubmatchIndex(text, -1) {
			if handleFuncWithMethodStarts[m[0]] {
				continue
			}
			path := text[m[2]:m[3]]
			line := base.FindLineNumber(text, m[0])
			nodes = append(nodes, mkGoEndpoint(filePath, "ANY", path, line, "mux"))
		}
	}

	// net/http Handle/HandleFunc
	for _, m := range goHttpHandleRE.FindAllStringSubmatchIndex(text, -1) {
		path := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		nodes = append(nodes, mkGoEndpoint(filePath, "ANY", path, line, "net_http"))
	}

	// Middleware via .Use(...)
	for _, m := range goUseMiddlewareRE.FindAllStringSubmatchIndex(text, -1) {
		mwName := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode(
			"go_web:"+filePath+":middleware:"+mwName+":"+strconv.Itoa(line),
			model.NodeMiddleware, mwName,
		)
		n.FQN = filePath + "::middleware:" + mwName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "GoWebDetector"
		n.Properties["framework"] = framework
		n.Properties["middleware"] = mwName
		nodes = append(nodes, n)
	}

	return detector.ResultOf(nodes, nil)
}

func mkGoEndpoint(filePath, method, path string, line int, framework string) *model.CodeNode {
	id := "go_web:" + filePath + ":" + method + ":" + path + ":" + strconv.Itoa(line)
	n := model.NewCodeNode(id, model.NodeEndpoint, method+" "+path)
	n.FQN = filePath + "::" + method + ":" + path
	n.FilePath = filePath
	n.LineStart = line
	n.Source = "GoWebDetector"
	n.Properties["framework"] = framework
	n.Properties["http_method"] = method
	n.Properties["path"] = path
	return n
}
