package frontend

import (
	"regexp"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// AngularComponentDetector mirrors Java AngularComponentDetector. Detects
// @Component / @Injectable / @Directive / @Pipe / @NgModule decorators in
// TypeScript and emits COMPONENT or MIDDLEWARE nodes accordingly.
type AngularComponentDetector struct{}

func NewAngularComponentDetector() *AngularComponentDetector { return &AngularComponentDetector{} }

func (AngularComponentDetector) Name() string                        { return "frontend.angular_components" }
func (AngularComponentDetector) SupportedLanguages() []string        { return []string{"typescript"} }
func (AngularComponentDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewAngularComponentDetector()) }

const propAngular = "angular"

var (
	// RE2 doesn't support DOTALL by default; use (?s) prefix.
	angularComponentDecorator  = regexp.MustCompile(`(?s)@Component\s*\(\s*\{.*?selector\s*:\s*['"]([^'"]+)['"].*?\}\s*\)\s*\n?\s*(?:export\s+)?class\s+(\w+)`)
	angularInjectableDecorator = regexp.MustCompile(`(?s)@Injectable\s*\(\s*\{.*?providedIn\s*:\s*['"]([\w]+)['"].*?\}\s*\)\s*\n?\s*(?:export\s+)?class\s+(\w+)`)
	angularDirectiveDecorator  = regexp.MustCompile(`(?s)@Directive\s*\(\s*\{.*?selector\s*:\s*['"]([^'"]+)['"].*?\}\s*\)\s*\n?\s*(?:export\s+)?class\s+(\w+)`)
	angularPipeDecorator       = regexp.MustCompile(`(?s)@Pipe\s*\(\s*\{.*?name\s*:\s*['"]([\w]+)['"].*?\}\s*\)\s*\n?\s*(?:export\s+)?class\s+(\w+)`)
	angularNgModuleDecorator   = regexp.MustCompile(`(?s)@NgModule\s*\(\s*\{.*?\}\s*\)\s*\n?\s*(?:export\s+)?class\s+(\w+)`)
)

func (d AngularComponentDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	nodes := []*model.CodeNode{}
	fp := ctx.FilePath
	seen := map[string]bool{}

	for _, m := range angularComponentDecorator.FindAllStringSubmatchIndex(text, -1) {
		selector := text[m[2]:m[3]]
		className := text[m[4]:m[5]]
		if seen[className] {
			continue
		}
		seen[className] = true
		n := base.CreateComponentNode(propAngular, fp, "component", className, model.NodeComponent, base.LineAt(text, m[0]))
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Properties["selector"] = selector
		n.Properties["decorator"] = "Component"
		nodes = append(nodes, n)
	}
	for _, m := range angularInjectableDecorator.FindAllStringSubmatchIndex(text, -1) {
		providedIn := text[m[2]:m[3]]
		className := text[m[4]:m[5]]
		if seen[className] {
			continue
		}
		seen[className] = true
		n := base.CreateComponentNode(propAngular, fp, "service", className, model.NodeMiddleware, base.LineAt(text, m[0]))
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Properties["provided_in"] = providedIn
		n.Properties["decorator"] = "Injectable"
		nodes = append(nodes, n)
	}
	for _, m := range angularDirectiveDecorator.FindAllStringSubmatchIndex(text, -1) {
		selector := text[m[2]:m[3]]
		className := text[m[4]:m[5]]
		if seen[className] {
			continue
		}
		seen[className] = true
		n := base.CreateComponentNode(propAngular, fp, "component", className, model.NodeComponent, base.LineAt(text, m[0]))
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Properties["selector"] = selector
		n.Properties["decorator"] = "Directive"
		nodes = append(nodes, n)
	}
	for _, m := range angularPipeDecorator.FindAllStringSubmatchIndex(text, -1) {
		pipeName := text[m[2]:m[3]]
		className := text[m[4]:m[5]]
		if seen[className] {
			continue
		}
		seen[className] = true
		n := base.CreateComponentNode(propAngular, fp, "component", className, model.NodeComponent, base.LineAt(text, m[0]))
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Properties["pipe_name"] = pipeName
		n.Properties["decorator"] = "Pipe"
		nodes = append(nodes, n)
	}
	for _, m := range angularNgModuleDecorator.FindAllStringSubmatchIndex(text, -1) {
		className := text[m[2]:m[3]]
		if seen[className] {
			continue
		}
		seen[className] = true
		n := base.CreateComponentNode(propAngular, fp, "component", className, model.NodeComponent, base.LineAt(text, m[0]))
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Properties["decorator"] = "NgModule"
		nodes = append(nodes, n)
	}
	return detector.ResultOf(nodes, nil)
}
