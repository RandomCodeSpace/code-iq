package typescript

import (
	"fmt"
	"regexp"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// NestJSGuardsDetector ports
// io.github.randomcodespace.iq.detector.typescript.NestJSGuardsDetector.
// Guard: requires `from '@nestjs/'` import.
type NestJSGuardsDetector struct{}

func NewNestJSGuardsDetector() *NestJSGuardsDetector { return &NestJSGuardsDetector{} }

func (NestJSGuardsDetector) Name() string                 { return "typescript.nestjs_guards" }
func (NestJSGuardsDetector) SupportedLanguages() []string { return []string{"typescript", "javascript"} }
func (NestJSGuardsDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewNestJSGuardsDetector()) }

var (
	useGuardsRE     = regexp.MustCompile(`@UseGuards\(\s*([^)]+)\)`)
	rolesDecorRE    = regexp.MustCompile(`@Roles\(\s*([^)]+)\)`)
	canActivateRE   = regexp.MustCompile(`(?:async\s+)?canActivate\s*\(`)
	authGuardArgRE  = regexp.MustCompile(`AuthGuard\(\s*['"](\w+)['"]\s*\)`)
	roleStringRE    = regexp.MustCompile(`['"]([\w\-]+)['"]`)
	guardIdentNameRE = regexp.MustCompile(`^\w+$`)
)

func (d NestJSGuardsDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if !nestjsImportRE.MatchString(text) {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName

	// @UseGuards(...)
	for _, m := range useGuardsRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		raw := text[m[2]:m[3]]
		for _, name := range parseGuardNames(raw) {
			id := fmt.Sprintf("auth:%s:UseGuards(%s):%d", filePath, name, line)
			n := model.NewCodeNode(id, model.NodeGuard, "UseGuards("+name+")")
			n.FQN = filePath + "::UseGuards(" + name + ")"
			n.Module = moduleName
			n.FilePath = filePath
			n.LineStart = line
			n.Source = "NestJSGuardsDetector"
			n.Confidence = model.ConfidenceLexical
			n.Annotations = append(n.Annotations, "@UseGuards")
			n.Properties["auth_type"] = "nestjs_guard"
			n.Properties["guard_name"] = name
			n.Properties["roles"] = []string{}
			nodes = append(nodes, n)
		}
	}

	// @Roles(...)
	for _, m := range rolesDecorRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		roles := parseRoles(text[m[2]:m[3]])
		id := fmt.Sprintf("auth:%s:Roles:%d", filePath, line)
		n := model.NewCodeNode(id, model.NodeGuard, "Roles("+strings.Join(roles, ", ")+")")
		n.FQN = filePath + "::Roles"
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "NestJSGuardsDetector"
		n.Confidence = model.ConfidenceLexical
		n.Annotations = append(n.Annotations, "@Roles")
		n.Properties["auth_type"] = "nestjs_guard"
		n.Properties["roles"] = roles
		nodes = append(nodes, n)
	}

	// canActivate(...)
	for _, m := range canActivateRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		id := fmt.Sprintf("auth:%s:canActivate:%d", filePath, line)
		n := model.NewCodeNode(id, model.NodeGuard, "canActivate()")
		n.FQN = filePath + "::canActivate"
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "NestJSGuardsDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["auth_type"] = "nestjs_guard"
		n.Properties["guard_impl"] = "canActivate"
		n.Properties["roles"] = []string{}
		nodes = append(nodes, n)
	}

	// AuthGuard('jwt')
	for _, m := range authGuardArgRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		strategy := text[m[2]:m[3]]
		id := fmt.Sprintf("auth:%s:AuthGuard(%s):%d", filePath, strategy, line)
		n := model.NewCodeNode(id, model.NodeGuard, "AuthGuard('"+strategy+"')")
		n.FQN = filePath + "::AuthGuard(" + strategy + ")"
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "NestJSGuardsDetector"
		n.Confidence = model.ConfidenceLexical
		n.Annotations = append(n.Annotations, "AuthGuard")
		n.Properties["auth_type"] = "nestjs_guard"
		n.Properties["strategy"] = strategy
		n.Properties["roles"] = []string{}
		nodes = append(nodes, n)
	}
	// Sort for determinism — multiple regex passes interleave by start order
	// per-RE, but slice order across REs depends on declaration order. Sorting
	// by id makes this independent of declaration order.
	sort.Slice(nodes, func(i, j int) bool { return nodes[i].ID < nodes[j].ID })
	return detector.ResultOf(nodes, nil)
}

func parseGuardNames(raw string) []string {
	var out []string
	for _, tok := range strings.Split(raw, ",") {
		t := strings.TrimSpace(tok)
		if t != "" && guardIdentNameRE.MatchString(t) {
			out = append(out, t)
		}
	}
	return out
}

func parseRoles(raw string) []string {
	var out []string
	for _, m := range roleStringRE.FindAllStringSubmatch(raw, -1) {
		out = append(out, m[1])
	}
	return out
}
