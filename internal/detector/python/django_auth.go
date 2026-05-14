package python

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// DjangoAuthDetector ports
// io.github.randomcodespace.iq.detector.python.DjangoAuthDetector.
type DjangoAuthDetector struct{}

func NewDjangoAuthDetector() *DjangoAuthDetector { return &DjangoAuthDetector{} }

func (DjangoAuthDetector) Name() string                        { return "django_auth" }
func (DjangoAuthDetector) SupportedLanguages() []string        { return []string{"python"} }
func (DjangoAuthDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewDjangoAuthDetector()) }

var (
	loginRequiredRE       = regexp.MustCompile(`@login_required\b`)
	permissionRequiredRE  = regexp.MustCompile(`@permission_required\(\s*["']([^"']*)["']`)
	userPassesTestRE      = regexp.MustCompile(`@user_passes_test\(\s*([^,)\s]+)`)
	djangoMixinClassRE    = regexp.MustCompile(`class\s+(\w+)\s*\(([^)]*)\):`)
)

var djangoAuthMixins = map[string]string{
	"LoginRequiredMixin":      "login_required",
	"PermissionRequiredMixin": "permission_required",
	"UserPassesTestMixin":     "user_passes_test",
}

func (d DjangoAuthDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName

	mk := func(id, label, decorator string, line int, props map[string]any) {
		n := model.NewCodeNode(id, model.NodeGuard, label)
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "DjangoAuthDetector"
		n.Confidence = model.ConfidenceLexical
		n.Annotations = []string{decorator}
		n.Properties["auth_type"] = "django"
		n.Properties["permissions"] = []string{}
		n.Properties["auth_required"] = true
		for k, v := range props {
			n.Properties[k] = v
		}
		nodes = append(nodes, n)
	}

	for _, m := range loginRequiredRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		mk(
			fmt.Sprintf("auth:%s:login_required:%d", filePath, line),
			"@login_required", "@login_required", line, nil,
		)
	}

	for _, m := range permissionRequiredRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		perm := text[m[2]:m[3]]
		mk(
			fmt.Sprintf("auth:%s:permission_required:%d", filePath, line),
			"@permission_required("+perm+")", "@permission_required", line,
			map[string]any{"permissions": []string{perm}},
		)
	}

	for _, m := range userPassesTestRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		testFunc := text[m[2]:m[3]]
		mk(
			fmt.Sprintf("auth:%s:user_passes_test:%d", filePath, line),
			"@user_passes_test("+testFunc+")", "@user_passes_test", line,
			map[string]any{"test_function": testFunc},
		)
	}

	for _, m := range djangoMixinClassRE.FindAllStringSubmatchIndex(text, -1) {
		className := text[m[2]:m[3]]
		basesStr := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])
		for _, b := range strings.Split(basesStr, ",") {
			b = strings.TrimSpace(b)
			if _, ok := djangoAuthMixins[b]; ok {
				mk(
					fmt.Sprintf("auth:%s:%s:%d", filePath, b, line),
					className+"("+b+")", "mixin:"+b, line,
					map[string]any{"mixin": b, "class_name": className},
				)
			}
		}
	}

	return detector.ResultOf(nodes, nil)
}
