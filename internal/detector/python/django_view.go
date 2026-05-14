package python

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// DjangoViewDetector ports
// io.github.randomcodespace.iq.detector.python.DjangoViewDetector.
// Phase 4 = regex (matches the Java detectWithRegex fallback path).
type DjangoViewDetector struct{}

func NewDjangoViewDetector() *DjangoViewDetector { return &DjangoViewDetector{} }

func (DjangoViewDetector) Name() string                        { return "python.django_views" }
func (DjangoViewDetector) SupportedLanguages() []string        { return []string{"python"} }
func (DjangoViewDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewDjangoViewDetector()) }

var (
	djangoUrlRE = regexp.MustCompile(`(?:path|re_path|url)\(\s*['"]([^'"]+)['"]\s*,\s*(\w[\w.]*)`)
	djangoCbvRE = regexp.MustCompile(`class\s+(\w+)\(([^)]*(?:View|ViewSet|Mixin)[^)]*)\):`)
)

func (d DjangoViewDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName

	if strings.Contains(text, "urlpatterns") {
		for _, m := range djangoUrlRE.FindAllStringSubmatchIndex(text, -1) {
			pathPattern := text[m[2]:m[3]]
			viewRef := text[m[4]:m[5]]
			line := base.FindLineNumber(text, m[0])
			id := "endpoint:" + moduleName + ":ALL:" + pathPattern
			n := model.NewCodeNode(id, model.NodeEndpoint, pathPattern)
			n.FQN = viewRef
			n.Module = moduleName
			n.FilePath = filePath
			n.LineStart = line
			n.Source = "DjangoViewDetector"
			n.Confidence = model.ConfidenceLexical
			n.Properties["protocol"] = "REST"
			n.Properties["path_pattern"] = pathPattern
			n.Properties["framework"] = "django"
			n.Properties["view_reference"] = viewRef
			nodes = append(nodes, n)
		}
	}

	for _, m := range djangoCbvRE.FindAllStringSubmatchIndex(text, -1) {
		className := text[m[2]:m[3]]
		bases := strings.TrimSpace(text[m[4]:m[5]])
		line := base.FindLineNumber(text, m[0])
		id := "class:" + filePath + "::" + className
		n := model.NewCodeNode(id, model.NodeClass, className)
		n.FQN = filePath + "::" + className
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "DjangoViewDetector"
		n.Confidence = model.ConfidenceLexical
		n.Annotations = []string{"extends:" + bases}
		n.Properties["framework"] = "django"
		n.Properties["stereotype"] = "view"
		nodes = append(nodes, n)
	}

	return detector.ResultOf(nodes, nil)
}
