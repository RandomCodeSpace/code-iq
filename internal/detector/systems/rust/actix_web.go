package rust

import (
	"regexp"
	"strconv"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// ActixWebDetector detects Actix-web and Axum endpoints, plus middleware
// layers and #[actix_web::main]/#[tokio::main] entry-point modules. Mirrors
// Java ActixWebDetector.
type ActixWebDetector struct{}

func NewActixWebDetector() *ActixWebDetector { return &ActixWebDetector{} }

func (ActixWebDetector) Name() string                        { return "actix_web" }
func (ActixWebDetector) SupportedLanguages() []string        { return []string{"rust"} }
func (ActixWebDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewActixWebDetector()) }

var (
	actixAttrRE         = regexp.MustCompile(`#\[(get|post|put|delete)\s*\(\s*"([^"]*)"\s*\)\s*\]`)
	actixHttpServerRE   = regexp.MustCompile(`HttpServer::new\s*\(`)
	actixRouteRE        = regexp.MustCompile(`\.route\s*\(\s*"([^"]*)"\s*,\s*web::(get|post|put|delete)\s*\(\s*\)\s*\.to\s*\(\s*(\w+)`)
	actixServiceResRE   = regexp.MustCompile(`\.service\s*\(\s*web::resource\s*\(\s*"([^"]*)"`)
	axumRouteRE         = regexp.MustCompile(`\.route\s*\(\s*"([^"]*)"\s*,\s*(get|post|put|delete)\s*\(\s*(\w+)\s*\)`)
	axumLayerRE         = regexp.MustCompile(`\.layer\s*\(\s*(\w+)`)
	actixMainAttrRE     = regexp.MustCompile(`#\[(actix_web::main|tokio::main)\]`)
	actixFnRE           = regexp.MustCompile(`(?:pub\s+)?(?:async\s+)?fn\s+(\w+)`)
)

var actixMarkers = []string{
	"#[get", "#[post", "#[put", "#[delete",
	"HttpServer::new", "web::get", "web::post", "web::resource",
	"Router::new", ".layer(", "actix_web::main", "tokio::main",
	"actix_web", "axum",
}

func hasActixMarker(text string) bool {
	for _, m := range actixMarkers {
		if strings.Contains(text, m) {
			return true
		}
	}
	return false
}

func (d ActixWebDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !hasActixMarker(text) {
		return detector.EmptyResult()
	}

	var nodes []*model.CodeNode
	filePath := ctx.FilePath
	lines := strings.Split(text, "\n")

	for i, line := range lines {
		lineno := i + 1

		// #[get("/path")] etc — peek up to 5 lines ahead for the fn name
		if m := actixAttrRE.FindStringSubmatch(line); len(m) >= 3 {
			method := strings.ToUpper(m[1])
			path := m[2]
			fnName := ""
			limit := i + 5
			if limit > len(lines) {
				limit = len(lines)
			}
			for k := i + 1; k < limit; k++ {
				if fm := actixFnRE.FindStringSubmatch(lines[k]); len(fm) >= 2 {
					fnName = fm[1]
					break
				}
			}
			n := model.NewCodeNode(
				"rust_web:"+filePath+":"+method+":"+path+":"+strconv.Itoa(lineno),
				model.NodeEndpoint, method+" "+path,
			)
			n.FQN = fnName
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "ActixWebDetector"
			n.Properties["framework"] = "actix_web"
			n.Properties["http_method"] = method
			n.Properties["path"] = path
			nodes = append(nodes, n)
		}

		// HttpServer::new(...) → module node
		if actixHttpServerRE.MatchString(line) {
			n := model.NewCodeNode(
				"rust_web:"+filePath+":http_server:"+strconv.Itoa(lineno),
				model.NodeModule, "HttpServer",
			)
			n.FQN = "HttpServer"
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "ActixWebDetector"
			n.Properties["framework"] = "actix_web"
			nodes = append(nodes, n)
		}

		// .route("/p", web::get().to(handler))
		if m := actixRouteRE.FindStringSubmatch(line); len(m) >= 4 {
			path := m[1]
			method := strings.ToUpper(m[2])
			handler := m[3]
			n := model.NewCodeNode(
				"rust_web:"+filePath+":"+method+":"+path+":"+strconv.Itoa(lineno),
				model.NodeEndpoint, method+" "+path,
			)
			n.FQN = handler
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "ActixWebDetector"
			n.Properties["framework"] = "actix_web"
			n.Properties["http_method"] = method
			n.Properties["path"] = path
			n.Properties["handler"] = handler
			nodes = append(nodes, n)
		}

		// .service(web::resource("/p"))
		if m := actixServiceResRE.FindStringSubmatch(line); len(m) >= 2 {
			path := m[1]
			n := model.NewCodeNode(
				"rust_web:"+filePath+":resource:"+path+":"+strconv.Itoa(lineno),
				model.NodeEndpoint, "resource "+path,
			)
			n.FQN = path
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "ActixWebDetector"
			n.Properties["framework"] = "actix_web"
			n.Properties["path"] = path
			nodes = append(nodes, n)
		}

		// axum: .route("/p", get(handler))
		if m := axumRouteRE.FindStringSubmatch(line); len(m) >= 4 {
			path := m[1]
			method := strings.ToUpper(m[2])
			handler := m[3]
			n := model.NewCodeNode(
				"rust_web:"+filePath+":"+method+":"+path+":"+strconv.Itoa(lineno),
				model.NodeEndpoint, method+" "+path,
			)
			n.FQN = handler
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "ActixWebDetector"
			n.Properties["framework"] = "axum"
			n.Properties["http_method"] = method
			n.Properties["path"] = path
			n.Properties["handler"] = handler
			nodes = append(nodes, n)
		}

		// axum .layer(Middleware)
		if m := axumLayerRE.FindStringSubmatch(line); len(m) >= 2 {
			mwName := m[1]
			n := model.NewCodeNode(
				"rust_web:"+filePath+":layer:"+mwName+":"+strconv.Itoa(lineno),
				model.NodeMiddleware, "layer("+mwName+")",
			)
			n.FQN = mwName
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "ActixWebDetector"
			n.Properties["framework"] = "axum"
			n.Properties["middleware"] = mwName
			nodes = append(nodes, n)
		}

		// #[actix_web::main] / #[tokio::main]
		if m := actixMainAttrRE.FindStringSubmatch(line); len(m) >= 2 {
			attr := m[1]
			framework := "tokio"
			if strings.Contains(attr, "actix") {
				framework = "actix_web"
			}
			n := model.NewCodeNode(
				"rust_web:"+filePath+":main:"+strconv.Itoa(lineno),
				model.NodeModule, "#["+attr+"]",
			)
			n.FQN = "main"
			n.FilePath = filePath
			n.LineStart = lineno
			n.Source = "ActixWebDetector"
			n.Properties["framework"] = framework
			nodes = append(nodes, n)
		}
	}

	return detector.ResultOf(nodes, nil)
}
