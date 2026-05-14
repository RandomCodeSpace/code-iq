package typescript

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// FastifyRouteDetector ports
// io.github.randomcodespace.iq.detector.typescript.FastifyRouteDetector.
// Guard: requires `import ... from 'fastify'` or `require('fastify')` —
// without this generic patterns like router.get() match Express.
type FastifyRouteDetector struct{}

func NewFastifyRouteDetector() *FastifyRouteDetector { return &FastifyRouteDetector{} }

func (FastifyRouteDetector) Name() string                 { return "fastify_routes" }
func (FastifyRouteDetector) SupportedLanguages() []string { return []string{"typescript", "javascript"} }
func (FastifyRouteDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewFastifyRouteDetector()) }

var (
	fastifyImportRE = regexp.MustCompile(
		`(?s)(?:import\s+.*?from\s+['"]fastify['"]|require\s*\(\s*['"]fastify['"]\s*\))`)
	fastifyShorthandRE = regexp.MustCompile(
		`(\w+)\.(get|post|put|delete|patch)\(\s*['"` + "`" + `]([^'"` + "`" + `]+)['"` + "`" + `]`)
	fastifyRouteObjRE = regexp.MustCompile(
		`(?s)(\w+)\.route\(\s*\{.*?method\s*:\s*['"` + "`" + `](\w+)['"` + "`" + `].*?url\s*:\s*['"` + "`" + `]([^'"` + "`" + `]+)['"` + "`" + `]`)
	fastifyRegisterRE = regexp.MustCompile(
		`(\w+)\.register\(\s*(\w+|import\([^)]+\))`)
	fastifyHookRE = regexp.MustCompile(
		`(\w+)\.addHook\(\s*['"` + "`" + `](\w+)['"` + "`" + `]`)
	fastifySchemaRE = regexp.MustCompile(`schema\s*:\s*\{([^}]+)\}`)
)

func (d FastifyRouteDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if !fastifyImportRE.MatchString(text) {
		return detector.EmptyResult()
	}

	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName
	seen := make(map[string]bool)

	// Shorthand routes: app.get(...), router.post(...)
	for _, m := range fastifyShorthandRE.FindAllStringSubmatchIndex(text, -1) {
		method := strings.ToUpper(text[m[4]:m[5]])
		path := text[m[6]:m[7]]
		line := base.FindLineNumber(text, m[0])
		id := fmt.Sprintf("fastify:%s:%s:%s:%d", filePath, method, path, line)
		seen[id] = true

		n := model.NewCodeNode(id, model.NodeEndpoint, method+" "+path)
		n.FQN = filePath + "::" + method + ":" + path
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "FastifyRouteDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["protocol"] = "REST"
		n.Properties["http_method"] = method
		n.Properties["path_pattern"] = path
		n.Properties["framework"] = "fastify"
		nodes = append(nodes, n)
	}

	// Route objects: app.route({ method: '...', url: '...' })
	for _, m := range fastifyRouteObjRE.FindAllStringSubmatchIndex(text, -1) {
		method := strings.ToUpper(text[m[4]:m[5]])
		path := text[m[6]:m[7]]
		line := base.FindLineNumber(text, m[0])
		id := fmt.Sprintf("fastify:%s:%s:%s:%d", filePath, method, path, line)
		if seen[id] {
			continue
		}
		seen[id] = true

		// Slice the route block to extract schema if present.
		routeBlock := text[m[0]:]
		if idx := strings.Index(routeBlock, ");"); idx >= 0 {
			routeBlock = routeBlock[:idx+2]
		}

		n := model.NewCodeNode(id, model.NodeEndpoint, method+" "+path)
		n.FQN = filePath + "::" + method + ":" + path
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "FastifyRouteDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["protocol"] = "REST"
		n.Properties["http_method"] = method
		n.Properties["path_pattern"] = path
		n.Properties["framework"] = "fastify"
		if sm := fastifySchemaRE.FindStringSubmatch(routeBlock); len(sm) >= 2 {
			n.Properties["schema"] = strings.TrimSpace(sm[1])
		}
		nodes = append(nodes, n)
	}

	// app.register(plugin) -> IMPORTS edge
	for _, m := range fastifyRegisterRE.FindAllStringSubmatchIndex(text, -1) {
		pluginRef := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])
		src := fmt.Sprintf("fastify:%s:server:%d", filePath, line)
		dst := fmt.Sprintf("fastify:%s:plugin:%s:%d", filePath, pluginRef, line)
		e := model.NewCodeEdge(src+"->"+dst, model.EdgeImports, src, dst)
		e.Source = "FastifyRouteDetector"
		e.Confidence = model.ConfidenceLexical
		e.Properties["framework"] = "fastify"
		e.Properties["plugin"] = pluginRef
		edges = append(edges, e)
	}

	// app.addHook(name) -> MIDDLEWARE node
	for _, m := range fastifyHookRE.FindAllStringSubmatchIndex(text, -1) {
		hookName := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])
		id := fmt.Sprintf("fastify:%s:hook:%s:%d", filePath, hookName, line)
		n := model.NewCodeNode(id, model.NodeMiddleware, "hook:"+hookName)
		n.FQN = filePath + "::hook:" + hookName
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "FastifyRouteDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["framework"] = "fastify"
		n.Properties["hook_name"] = hookName
		nodes = append(nodes, n)
	}

	return detector.ResultOf(nodes, edges)
}
