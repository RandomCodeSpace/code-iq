package typescript

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// RemixRouteDetector ports
// io.github.randomcodespace.iq.detector.typescript.RemixRouteDetector.
type RemixRouteDetector struct{}

func NewRemixRouteDetector() *RemixRouteDetector { return &RemixRouteDetector{} }

func (RemixRouteDetector) Name() string                 { return "remix_routes" }
func (RemixRouteDetector) SupportedLanguages() []string { return []string{"typescript", "javascript"} }
func (RemixRouteDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewRemixRouteDetector()) }

var (
	remixLoaderRE     = regexp.MustCompile(`export\s+(?:async\s+)?function\s+loader\s*\(`)
	remixActionRE     = regexp.MustCompile(`export\s+(?:async\s+)?function\s+action\s*\(`)
	remixDefaultCompRE = regexp.MustCompile(`export\s+default\s+function\s+(\w*)\s*\(`)
	remixUseLoaderDataRE = regexp.MustCompile(`\buseLoaderData\s*\(\s*\)`)
	remixUseActionDataRE = regexp.MustCompile(`\buseActionData\s*\(\s*\)`)
	remixExtensionRE    = regexp.MustCompile(`\.(tsx?|jsx?)$`)
	remixTrailingDotSlashRE = regexp.MustCompile(`[/.]$`)
)

func (d RemixRouteDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName
	routePath := deriveRemixRoutePath(filePath)
	var nodes []*model.CodeNode

	addNode := func(id, label, fqn, kind string, line int, props map[string]any, nk model.NodeKind) {
		n := model.NewCodeNode(id, nk, label)
		n.FQN = fqn
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "RemixRouteDetector"
		n.Confidence = model.ConfidenceLexical
		for k, v := range props {
			n.Properties[k] = v
		}
		if routePath != "" {
			n.Properties["route_path"] = routePath
		}
		nodes = append(nodes, n)
	}

	// loader exports
	for _, m := range remixLoaderRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		labelPath := routePath
		if labelPath == "" {
			labelPath = filePath
		}
		addNode(
			fmt.Sprintf("remix:%s:loader:%d", filePath, line),
			"loader "+labelPath,
			filePath+"::loader",
			"loader", line,
			map[string]any{"framework": "remix", "type": "loader", "http_method": "GET"},
			model.NodeEndpoint,
		)
	}

	// action exports
	for _, m := range remixActionRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		labelPath := routePath
		if labelPath == "" {
			labelPath = filePath
		}
		addNode(
			fmt.Sprintf("remix:%s:action:%d", filePath, line),
			"action "+labelPath,
			filePath+"::action",
			"action", line,
			map[string]any{"framework": "remix", "type": "action", "http_method": "POST"},
			model.NodeEndpoint,
		)
	}

	// Default component export
	hasLoaderData := remixUseLoaderDataRE.MatchString(text)
	hasActionData := remixUseActionDataRE.MatchString(text)
	for _, m := range remixDefaultCompRE.FindAllStringSubmatchIndex(text, -1) {
		name := ""
		if m[2] >= 0 {
			name = text[m[2]:m[3]]
		}
		if name == "" {
			name = "default"
		}
		line := base.FindLineNumber(text, m[0])
		props := map[string]any{
			"framework": "remix",
			"type":      "component",
		}
		if hasLoaderData {
			props["uses_loader_data"] = true
		}
		if hasActionData {
			props["uses_action_data"] = true
		}
		addNode(
			fmt.Sprintf("remix:%s:component:%s", filePath, name),
			name,
			filePath+"::"+name,
			"component", line,
			props,
			model.NodeComponent,
		)
	}

	return detector.ResultOf(nodes, nil)
}

// deriveRemixRoutePath mirrors Java's deriveRoutePath: only applies to files
// under `app/routes/`; returns "" otherwise. Handles _index and $param + _ suffix.
func deriveRemixRoutePath(filePath string) string {
	if !strings.Contains(filePath, "app/routes/") {
		return ""
	}
	idx := strings.Index(filePath, "app/routes/")
	segment := filePath[idx+len("app/routes/"):]
	segment = remixExtensionRE.ReplaceAllString(segment, "")

	if segment == "_index" || strings.HasSuffix(segment, "/_index") {
		prefix := segment[:strings.LastIndex(segment, "_index")]
		prefix = remixTrailingDotSlashRE.ReplaceAllString(prefix, "")
		if prefix == "" {
			return "/"
		}
		return "/" + strings.ReplaceAll(prefix, ".", "/")
	}

	parts := strings.Split(segment, ".")
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		switch {
		case strings.HasPrefix(p, "$"):
			out = append(out, ":"+p[1:])
		case strings.HasSuffix(p, "_"):
			out = append(out, p[:len(p)-1])
		default:
			out = append(out, p)
		}
	}
	return "/" + strings.Join(out, "/")
}
