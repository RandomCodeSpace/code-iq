package java

import (
	"regexp"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// SpringSecurityDetector mirrors Java SpringSecurityDetector regex tier.
// Emits GUARD nodes for security annotations + .authorizeHttpRequests() calls.
type SpringSecurityDetector struct{}

func NewSpringSecurityDetector() *SpringSecurityDetector { return &SpringSecurityDetector{} }

func (SpringSecurityDetector) Name() string                 { return "spring_security" }
func (SpringSecurityDetector) SupportedLanguages() []string { return []string{"java"} }
func (SpringSecurityDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewSpringSecurityDetector()) }

var (
	ssSecuredRE        = regexp.MustCompile(`@Secured\(\s*(?:\{([^}]*)\}|"([^"]*)")\s*\)`)
	ssPreAuthorizeRE   = regexp.MustCompile(`@PreAuthorize\(\s*"([^"]*)"\s*\)`)
	ssRolesAllowedRE   = regexp.MustCompile(`@RolesAllowed\(\s*(?:\{([^}]*)\}|"([^"]*)")\s*\)`)
	ssEnableWebSecRE   = regexp.MustCompile(`@EnableWebSecurity\b`)
	ssEnableMethodSecRE = regexp.MustCompile(`@EnableMethodSecurity\b`)
	ssFilterChainRE    = regexp.MustCompile(`(?:public\s+)?SecurityFilterChain\s+(\w+)\s*\(`)
	ssAuthorizeRE      = regexp.MustCompile(`\.authorizeHttpRequests\s*\(`)
	ssRoleStrRE        = regexp.MustCompile(`"([^"]*)"`)
	ssHasRoleRE        = regexp.MustCompile(`hasRole\(\s*'([^']*)'\s*\)`)
	ssHasAnyRoleRE     = regexp.MustCompile(`hasAnyRole\(\s*([^)]+)\)`)
	ssSingleQuotedRE   = regexp.MustCompile(`'([^']*)'`)
)

func (d SpringSecurityDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}

	var nodes []*model.CodeNode

	for _, m := range ssSecuredRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		var multi, single string
		if m[2] >= 0 {
			multi = text[m[2]:m[3]]
		}
		if m[4] >= 0 {
			single = text[m[4]:m[5]]
		}
		roles := extractRolesFromAnnotation(multi, single)
		nodes = append(nodes, ssGuardNode(
			"auth:"+ctx.FilePath+":Secured:"+itoaQ(line),
			"@Secured", line, ctx, []string{"@Secured"},
			map[string]any{"auth_type": "spring_security", "roles": roles, "auth_required": true},
		))
	}

	for _, m := range ssPreAuthorizeRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		expr := text[m[2]:m[3]]
		roles := extractRolesFromSpel(expr)
		props := map[string]any{
			"auth_type":     "spring_security",
			"roles":         roles,
			"expression":    expr,
			"auth_required": true,
		}
		nodes = append(nodes, ssGuardNode(
			"auth:"+ctx.FilePath+":PreAuthorize:"+itoaQ(line),
			"@PreAuthorize", line, ctx, []string{"@PreAuthorize"}, props,
		))
	}

	for _, m := range ssRolesAllowedRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		var multi, single string
		if m[2] >= 0 {
			multi = text[m[2]:m[3]]
		}
		if m[4] >= 0 {
			single = text[m[4]:m[5]]
		}
		roles := extractRolesFromAnnotation(multi, single)
		nodes = append(nodes, ssGuardNode(
			"auth:"+ctx.FilePath+":RolesAllowed:"+itoaQ(line),
			"@RolesAllowed", line, ctx, []string{"@RolesAllowed"},
			map[string]any{"auth_type": "spring_security", "roles": roles, "auth_required": true},
		))
	}

	for _, m := range ssEnableWebSecRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		nodes = append(nodes, ssGuardNode(
			"auth:"+ctx.FilePath+":EnableWebSecurity:"+itoaQ(line),
			"@EnableWebSecurity", line, ctx, []string{"@EnableWebSecurity"},
			map[string]any{"auth_type": "spring_security", "roles": []string{}, "auth_required": true},
		))
	}

	for _, m := range ssEnableMethodSecRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		nodes = append(nodes, ssGuardNode(
			"auth:"+ctx.FilePath+":EnableMethodSecurity:"+itoaQ(line),
			"@EnableMethodSecurity", line, ctx, []string{"@EnableMethodSecurity"},
			map[string]any{"auth_type": "spring_security", "roles": []string{}, "auth_required": true},
		))
	}

	for _, m := range ssFilterChainRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		methodName := text[m[2]:m[3]]
		nodes = append(nodes, ssGuardNode(
			"auth:"+ctx.FilePath+":SecurityFilterChain:"+itoaQ(line),
			"SecurityFilterChain:"+methodName, line, ctx, nil,
			map[string]any{
				"auth_type": "spring_security", "roles": []string{},
				"method_name": methodName, "auth_required": true,
			},
		))
	}

	for _, m := range ssAuthorizeRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		nodes = append(nodes, ssGuardNode(
			"auth:"+ctx.FilePath+":authorizeHttpRequests:"+itoaQ(line),
			".authorizeHttpRequests()", line, ctx, nil,
			map[string]any{"auth_type": "spring_security", "roles": []string{}, "auth_required": true},
		))
	}

	return detector.ResultOf(nodes, nil)
}

func ssGuardNode(id, label string, line int, ctx *detector.Context, annotations []string, props map[string]any) *model.CodeNode {
	n := model.NewCodeNode(id, model.NodeGuard, label)
	n.FilePath = ctx.FilePath
	n.LineStart = line
	n.Source = "SpringSecurityDetector"
	if annotations != nil {
		n.Annotations = append(n.Annotations, annotations...)
	}
	for k, v := range props {
		n.Properties[k] = v
	}
	n.Properties["framework"] = "spring_boot"
	return n
}

func extractRolesFromAnnotation(multi, single string) []string {
	if single != "" {
		return []string{single}
	}
	if multi != "" {
		var roles []string
		for _, m := range ssRoleStrRE.FindAllStringSubmatch(multi, -1) {
			roles = append(roles, m[1])
		}
		return roles
	}
	return []string{}
}

func extractRolesFromSpel(expr string) []string {
	var roles []string
	for _, m := range ssHasRoleRE.FindAllStringSubmatch(expr, -1) {
		roles = append(roles, m[1])
	}
	for _, m := range ssHasAnyRoleRE.FindAllStringSubmatch(expr, -1) {
		inner := m[1]
		for _, q := range ssSingleQuotedRE.FindAllStringSubmatch(inner, -1) {
			roles = append(roles, q[1])
		}
	}
	if roles == nil {
		return []string{}
	}
	return roles
}
