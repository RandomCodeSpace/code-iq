package kotlin

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// KtorRouteDetector mirrors Java KtorRouteDetector regex tier. Detects
// `routing { get("/p") { } }` blocks, `route("/api") {` prefixes,
// `authenticate("...") {` guards, and `install(...)` features.
type KtorRouteDetector struct{}

func NewKtorRouteDetector() *KtorRouteDetector { return &KtorRouteDetector{} }

func (KtorRouteDetector) Name() string                 { return "ktor_routes" }
func (KtorRouteDetector) SupportedLanguages() []string { return []string{"kotlin"} }
func (KtorRouteDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewKtorRouteDetector()) }

var (
	ktorEndpointRE     = regexp.MustCompile(`\b(get|post|put|delete|patch)\(\s*"([^"]+)"\s*\)\s*\{`)
	ktorRoutingRE      = regexp.MustCompile(`\brouting\s*\{`)
	ktorRoutePrefixRE  = regexp.MustCompile(`\broute\(\s*"([^"]+)"\s*\)\s*\{`)
	ktorInstallRE      = regexp.MustCompile(`\binstall\(\s*(\w+)\s*\)`)
	ktorAuthenticateRE = regexp.MustCompile(`\bauthenticate\(\s*"([^"]+)"\s*\)\s*\{`)
)

// buildPrefixMap walks the source line by line, tracking brace depth, to map
// each line to the active `route("...")` prefix chain. Mirrors Java's
// buildPrefixMap.
func buildPrefixMap(text string) map[int]string {
	prefixes := map[int]string{}
	type activePrefix struct {
		prefixIdx  int
		braceDepth int
	}
	var active []activePrefix
	var prefixValues []string
	braceDepth := 0
	lines := strings.Split(text, "\n")

	for i, line := range lines {
		braceDepth += strings.Count(line, "{") - strings.Count(line, "}")
		if m := ktorRoutePrefixRE.FindStringSubmatch(line); m != nil {
			prefixValues = append(prefixValues, m[1])
			active = append(active, activePrefix{prefixIdx: len(prefixValues) - 1, braceDepth: braceDepth})
		}
		for len(active) > 0 && braceDepth < active[len(active)-1].braceDepth {
			active = active[:len(active)-1]
		}
		if len(active) > 0 {
			var sb strings.Builder
			for _, ap := range active {
				sb.WriteString(prefixValues[ap.prefixIdx])
			}
			prefixes[i+1] = sb.String()
		}
	}
	return prefixes
}

func (d KtorRouteDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	fp := ctx.FilePath
	var nodes []*model.CodeNode

	prefixMap := buildPrefixMap(text)

	// routing { ... }
	for _, m := range ktorRoutingRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode("ktor:"+fp+":routing:"+itoa(line), model.NodeModule, "routing")
		n.FQN = fp + "::routing"
		n.FilePath = fp
		n.LineStart = line
		n.Properties["framework"] = "ktor"
		n.Properties["type"] = "router"
		nodes = append(nodes, n)
	}

	// HTTP endpoints
	for _, m := range ktorEndpointRE.FindAllStringSubmatchIndex(text, -1) {
		method := strings.ToUpper(text[m[2]:m[3]])
		rawPath := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])
		prefix := prefixMap[line]
		path := prefix + rawPath
		n := model.NewCodeNode(
			"ktor:"+fp+":"+method+":"+path+":"+itoa(line),
			model.NodeEndpoint,
			method+" "+path,
		)
		n.FQN = fp + "::" + method + ":" + path
		n.FilePath = fp
		n.LineStart = line
		n.Properties["protocol"] = "REST"
		n.Properties["http_method"] = method
		n.Properties["path_pattern"] = path
		n.Properties["framework"] = "ktor"
		nodes = append(nodes, n)
	}

	// install(Feature)
	for _, m := range ktorInstallRE.FindAllStringSubmatchIndex(text, -1) {
		feature := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode(
			"ktor:"+fp+":install:"+feature+":"+itoa(line),
			model.NodeMiddleware,
			"install:"+feature,
		)
		n.FQN = fp + "::install:" + feature
		n.FilePath = fp
		n.LineStart = line
		n.Properties["framework"] = "ktor"
		n.Properties["feature"] = feature
		nodes = append(nodes, n)
	}

	// authenticate("name") { ... }
	for _, m := range ktorAuthenticateRE.FindAllStringSubmatchIndex(text, -1) {
		authName := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode(
			"ktor:"+fp+":auth:"+authName+":"+itoa(line),
			model.NodeGuard,
			"authenticate:"+authName,
		)
		n.FQN = fp + "::authenticate:" + authName
		n.FilePath = fp
		n.LineStart = line
		n.Properties["framework"] = "ktor"
		n.Properties["auth_name"] = authName
		nodes = append(nodes, n)
	}

	return detector.ResultOf(nodes, nil)
}

// itoa avoids importing strconv across every detector — small helper.
func itoa(i int) string {
	// Always positive line numbers, simple ASCII.
	if i == 0 {
		return "0"
	}
	var buf [20]byte
	n := len(buf)
	for i > 0 {
		n--
		buf[n] = byte('0' + i%10)
		i /= 10
	}
	return string(buf[n:])
}
