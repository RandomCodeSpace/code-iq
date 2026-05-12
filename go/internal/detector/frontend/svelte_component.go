package frontend

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// SvelteComponentDetector mirrors Java SvelteComponentDetector.
type SvelteComponentDetector struct{}

func NewSvelteComponentDetector() *SvelteComponentDetector { return &SvelteComponentDetector{} }

func (SvelteComponentDetector) Name() string { return "frontend.svelte_components" }
func (SvelteComponentDetector) SupportedLanguages() []string {
	return []string{"typescript", "javascript", "svelte"}
}
func (SvelteComponentDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewSvelteComponentDetector()) }

var (
	sveltePropRE     = regexp.MustCompile(`export\s+let\s+(\w+)`)
	svelteReactiveRE = regexp.MustCompile(`(?m)^\s*\$:`)
	svelteScriptRE   = regexp.MustCompile(`(?m)^<script\b`)
	// RE2 has no lookahead. Match `<tag` where tag starts with a letter
	// and isn't `script`, `style`, or a closing tag. We post-filter in code.
	svelteTemplateRE = regexp.MustCompile(`(?m)^<([a-zA-Z]\w*)[\s>]`)
)

func (d SvelteComponentDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	hasProps := sveltePropRE.FindStringIndex(text) != nil
	hasReactive := svelteReactiveRE.FindStringIndex(text) != nil
	hasScript := svelteScriptRE.FindStringIndex(text) != nil
	hasTemplate := false
	for _, m := range svelteTemplateRE.FindAllStringSubmatch(text, -1) {
		tag := strings.ToLower(m[1])
		if tag != "script" && tag != "style" {
			hasTemplate = true
			break
		}
	}
	if !(hasProps || hasReactive || (hasScript && hasTemplate)) {
		return detector.EmptyResult()
	}

	fp := ctx.FilePath
	normalized := strings.ReplaceAll(fp, "\\", "/")
	name := normalized
	if i := strings.LastIndex(normalized, "/"); i >= 0 {
		name = normalized[i+1:]
	}
	if i := strings.LastIndex(name, "."); i > 0 {
		name = name[:i]
	}

	var props []string
	for _, m := range sveltePropRE.FindAllStringSubmatch(text, -1) {
		props = append(props, m[1])
	}
	reactiveCount := len(svelteReactiveRE.FindAllStringIndex(text, -1))

	firstLine := 1
	for _, re := range []*regexp.Regexp{svelteScriptRE, sveltePropRE, svelteReactiveRE} {
		if loc := re.FindStringIndex(text); loc != nil {
			firstLine = base.LineAt(text, loc[0])
			break
		}
	}

	n := model.NewCodeNode("svelte:"+fp+":component:"+name, model.NodeComponent, name)
	n.FQN = fp + "::" + name
	n.FilePath = fp
	n.LineStart = firstLine
	n.Source = "SvelteComponentDetector"
	n.Confidence = base.RegexDetectorDefaultConfidence
	n.Properties["framework"] = "svelte"
	n.Properties["props"] = props
	n.Properties["reactive_statements"] = reactiveCount
	return detector.ResultOf([]*model.CodeNode{n}, nil)
}
