package frontend

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// FrontendRouteDetector mirrors Java FrontendRouteDetector. Detects React
// Router <Route> elements, Vue Router routes arrays, Angular Router config,
// and Next.js file-based routes (pages/ and app/).
type FrontendRouteDetector struct{}

func NewFrontendRouteDetector() *FrontendRouteDetector { return &FrontendRouteDetector{} }

func (FrontendRouteDetector) Name() string { return "frontend.frontend_routes" }
func (FrontendRouteDetector) SupportedLanguages() []string {
	return []string{"typescript", "javascript", "vue", "svelte"}
}
func (FrontendRouteDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewFrontendRouteDetector()) }

var (
	frReactRouteComp     = regexp.MustCompile(`<Route\s+[^>]*?path\s*=\s*["']([^"']+)["'][^>]*?component\s*=\s*\{(\w+)\}`)
	frReactRouteElement  = regexp.MustCompile(`<Route\s+[^>]*?path\s*=\s*["']([^"']+)["'][^>]*?element\s*=\s*\{<(\w+)`)
	frReactRouteBare     = regexp.MustCompile(`<Route\s+[^>]*?path\s*=\s*["']([^"']+)["']`)
	frVueRoute           = regexp.MustCompile(`\{\s*path\s*:\s*['"]([^'"]+)['"](?:.*?component\s*:\s*(\w+))?`)
	frVueCreateRouter    = regexp.MustCompile(`createRouter\s*\(`)
	frVueRoutesArray     = regexp.MustCompile(`\broutes\s*:\s*\[`)
	frAngularRouterModul = regexp.MustCompile(`RouterModule\.for(?:Root|Child)\s*\(`)
	frNextjsPages        = regexp.MustCompile(`^pages/(.+)\.(tsx|ts|jsx|js)$`)
	frNextjsApp          = regexp.MustCompile(`^app/(.+)/page\.(tsx|ts|jsx|js)$`)
)

func (FrontendRouteDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" && ctx.FilePath == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	frDetectNextjs(ctx, &nodes)
	frDetectReact(ctx, text, &nodes, &edges)
	frDetectVue(ctx, text, &nodes, &edges)
	frDetectAngular(ctx, text, &nodes, &edges)

	return detector.ResultOf(nodes, edges)
}

func frDetectNextjs(ctx *detector.Context, nodes *[]*model.CodeNode) {
	fp := ctx.FilePath
	if m := frNextjsPages.FindStringSubmatch(fp); m != nil {
		routePath := frNextjsPagesPath(m[1])
		*nodes = append(*nodes, frRouteNode("route:"+fp+":nextjs:"+routePath, routePath, "nextjs", ctx, 1))
		return
	}
	if m := frNextjsApp.FindStringSubmatch(fp); m != nil {
		raw := strings.ReplaceAll(m[1], "\\", "/")
		routePath := "/" + raw
		*nodes = append(*nodes, frRouteNode("route:"+fp+":nextjs:"+routePath, routePath, "nextjs", ctx, 1))
	}
}

func frNextjsPagesPath(raw string) string {
	parts := strings.Split(strings.ReplaceAll(raw, "\\", "/"), "/")
	if len(parts) > 0 && parts[len(parts)-1] == "index" {
		parts = parts[:len(parts)-1]
	}
	if len(parts) == 0 {
		return "/"
	}
	return "/" + strings.Join(parts, "/")
}

func frDetectReact(ctx *detector.Context, text string, nodes *[]*model.CodeNode, edges *[]*model.CodeEdge) {
	seen := map[string]bool{}
	for _, re := range []*regexp.Regexp{frReactRouteComp, frReactRouteElement} {
		for _, m := range re.FindAllStringSubmatchIndex(text, -1) {
			path := text[m[2]:m[3]]
			comp := text[m[4]:m[5]]
			if seen[path] {
				continue
			}
			seen[path] = true
			line := base.LineAt(text, m[0])
			id := "route:" + ctx.FilePath + ":react:" + path
			*nodes = append(*nodes, frRouteNode(id, path, "react", ctx, line))
			*edges = append(*edges, model.NewCodeEdge(id+":renders:"+comp, model.EdgeRenders, id, comp))
		}
	}
	for _, m := range frReactRouteBare.FindAllStringSubmatchIndex(text, -1) {
		path := text[m[2]:m[3]]
		if seen[path] {
			continue
		}
		seen[path] = true
		line := base.LineAt(text, m[0])
		*nodes = append(*nodes, frRouteNode("route:"+ctx.FilePath+":react:"+path, path, "react", ctx, line))
	}
}

func frDetectVue(ctx *detector.Context, text string, nodes *[]*model.CodeNode, edges *[]*model.CodeEdge) {
	if frVueCreateRouter.FindStringIndex(text) == nil && frVueRoutesArray.FindStringIndex(text) == nil {
		return
	}
	for _, m := range frVueRoute.FindAllStringSubmatchIndex(text, -1) {
		path := text[m[2]:m[3]]
		var comp string
		if m[4] >= 0 {
			comp = text[m[4]:m[5]]
		}
		line := base.LineAt(text, m[0])
		id := "route:" + ctx.FilePath + ":vue:" + path
		*nodes = append(*nodes, frRouteNode(id, path, "vue", ctx, line))
		if comp != "" {
			*edges = append(*edges, model.NewCodeEdge(id+":renders:"+comp, model.EdgeRenders, id, comp))
		}
	}
}

func frDetectAngular(ctx *detector.Context, text string, nodes *[]*model.CodeNode, edges *[]*model.CodeEdge) {
	if frAngularRouterModul.FindStringIndex(text) == nil {
		return
	}
	for _, m := range frVueRoute.FindAllStringSubmatchIndex(text, -1) {
		path := text[m[2]:m[3]]
		var comp string
		if m[4] >= 0 {
			comp = text[m[4]:m[5]]
		}
		line := base.LineAt(text, m[0])
		id := "route:" + ctx.FilePath + ":angular:" + path
		*nodes = append(*nodes, frRouteNode(id, path, "angular", ctx, line))
		if comp != "" {
			*edges = append(*edges, model.NewCodeEdge(id+":renders:"+comp, model.EdgeRenders, id, comp))
		}
	}
}

func frRouteNode(id, path, framework string, ctx *detector.Context, line int) *model.CodeNode {
	n := model.NewCodeNode(id, model.NodeEndpoint, "route "+path)
	n.FQN = ctx.FilePath + "::route:" + path
	n.FilePath = ctx.FilePath
	n.LineStart = line
	n.Source = "FrontendRouteDetector"
	n.Confidence = base.RegexDetectorDefaultConfidence
	n.Properties["protocol"] = "frontend_route"
	n.Properties["framework"] = framework
	n.Properties["route_path"] = path
	return n
}
