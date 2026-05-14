package frontend

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// VueComponentDetector mirrors Java VueComponentDetector: defineComponent /
// Options API / <script setup> / composables (use* hooks).
type VueComponentDetector struct{}

func NewVueComponentDetector() *VueComponentDetector { return &VueComponentDetector{} }

func (VueComponentDetector) Name() string                        { return "frontend.vue_components" }
func (VueComponentDetector) SupportedLanguages() []string        { return []string{"typescript", "javascript", "vue"} }
func (VueComponentDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewVueComponentDetector()) }

const propVue = "vue"

var (
	vueDefineComponentName = regexp.MustCompile(`(?s)export\s+default\s+defineComponent\s*\(\s*\{[^}]*?name\s*:\s*['"](\w+)['"]`)
	vueOptionsApiName      = regexp.MustCompile(`export\s+default\s+\{\s*name\s*:\s*['"](\w+)['"]`)
	vueScriptSetup         = regexp.MustCompile(`<script\s+setup(?:\s+lang\s*=\s*['"](?:ts|js)['"])?\s*>`)
	vueExportFuncHook      = regexp.MustCompile(`export\s+function\s+(use[A-Z]\w*)\s*\(`)
	vueExportConstHook     = regexp.MustCompile(`export\s+const\s+(use[A-Z]\w*)\s*=\s*`)
)

func (d VueComponentDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	nodes := []*model.CodeNode{}
	fp := ctx.FilePath
	seen := map[string]bool{}

	for _, m := range vueDefineComponentName.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		if seen[name] {
			continue
		}
		seen[name] = true
		n := base.CreateComponentNode(propVue, fp, "component", name, model.NodeComponent, base.LineAt(text, m[0]))
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Properties["api_style"] = "composition"
		nodes = append(nodes, n)
	}
	for _, m := range vueOptionsApiName.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		if seen[name] {
			continue
		}
		seen[name] = true
		n := base.CreateComponentNode(propVue, fp, "component", name, model.NodeComponent, base.LineAt(text, m[0]))
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Properties["api_style"] = "options"
		nodes = append(nodes, n)
	}
	for _, m := range vueScriptSetup.FindAllStringSubmatchIndex(text, -1) {
		name := vueScriptSetupName(fp)
		if name == "" || seen[name] {
			continue
		}
		seen[name] = true
		n := base.CreateComponentNode(propVue, fp, "component", name, model.NodeComponent, base.LineAt(text, m[0]))
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Properties["api_style"] = "script_setup"
		nodes = append(nodes, n)
	}
	hooks := map[string]bool{}
	for _, re := range []*regexp.Regexp{vueExportFuncHook, vueExportConstHook} {
		for _, m := range re.FindAllStringSubmatchIndex(text, -1) {
			name := text[m[2]:m[3]]
			if hooks[name] {
				continue
			}
			hooks[name] = true
			n := base.CreateComponentNode(propVue, fp, "hook", name, model.NodeHook, base.LineAt(text, m[0]))
			n.Confidence = base.RegexDetectorDefaultConfidence
			nodes = append(nodes, n)
		}
	}
	return &detector.Result{Nodes: nodes}
}

func vueScriptSetupName(filePath string) string {
	p := strings.ReplaceAll(filePath, "\\", "/")
	if i := strings.LastIndex(p, "/"); i >= 0 {
		p = p[i+1:]
	}
	if name, ok := strings.CutSuffix(p, ".vue"); ok {
		return name
	}
	return ""
}
