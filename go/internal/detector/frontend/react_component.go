package frontend

import (
	"regexp"
	"sort"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// ReactComponentDetector mirrors Java ReactComponentDetector. Emits a
// COMPONENT node per React function / class component and a HOOK node per
// custom hook (`use*` exports). For each component, emits a RENDERS edge to
// each capitalized JSX tag found within that component's body scope.
type ReactComponentDetector struct{}

func NewReactComponentDetector() *ReactComponentDetector { return &ReactComponentDetector{} }

func (ReactComponentDetector) Name() string                        { return "frontend.react_components" }
func (ReactComponentDetector) SupportedLanguages() []string        { return []string{"typescript", "javascript"} }
func (ReactComponentDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewReactComponentDetector()) }

const propReact = "react"

var (
	reactExportDefaultFunc       = regexp.MustCompile(`export\s+default\s+function\s+([A-Z]\w*)\s*\(`)
	reactExportConstArrow        = regexp.MustCompile(`export\s+const\s+([A-Z]\w*)\s*=\s*\(`)
	reactExportConstFC           = regexp.MustCompile(`export\s+const\s+([A-Z]\w*)\s*:\s*React\.FC`)
	reactClassExtendsReact       = regexp.MustCompile(`class\s+([A-Z]\w*)\s+extends\s+React\.Component`)
	reactClassExtendsComp        = regexp.MustCompile(`class\s+([A-Z]\w*)\s+extends\s+Component\b`)
	reactExportFuncHook          = regexp.MustCompile(`export\s+function\s+(use[A-Z]\w*)\s*\(`)
	reactExportConstHook         = regexp.MustCompile(`export\s+const\s+(use[A-Z]\w*)\s*=\s*`)
	reactJSXTag                  = regexp.MustCompile(`<([A-Z]\w*)\b`)
	reactComponentRegexFunc      = []*regexp.Regexp{reactExportDefaultFunc, reactExportConstArrow, reactExportConstFC}
	reactComponentRegexClass     = []*regexp.Regexp{reactClassExtendsReact, reactClassExtendsComp}
	reactHookRegexes             = []*regexp.Regexp{reactExportFuncHook, reactExportConstHook}
)

func (d ReactComponentDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}
	fp := ctx.FilePath

	type compEntry struct {
		name       string
		sourceID   string
		matchStart int
	}
	var compEntries []compEntry
	seen := map[string]bool{}

	addFunc := func(name string, start int) {
		if seen[name] {
			return
		}
		seen[name] = true
		sourceID := "react:" + fp + ":component:" + name
		n := base.CreateComponentNode(propReact, fp, "component", name, model.NodeComponent, base.LineAt(text, start))
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Properties["component_type"] = "function"
		nodes = append(nodes, n)
		compEntries = append(compEntries, compEntry{name, sourceID, start})
	}
	addClass := func(name string, start int) {
		if seen[name] {
			return
		}
		seen[name] = true
		sourceID := "react:" + fp + ":component:" + name
		n := base.CreateComponentNode(propReact, fp, "component", name, model.NodeComponent, base.LineAt(text, start))
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Properties["component_type"] = "class"
		nodes = append(nodes, n)
		compEntries = append(compEntries, compEntry{name, sourceID, start})
	}

	for _, re := range reactComponentRegexFunc {
		for _, m := range re.FindAllStringSubmatchIndex(text, -1) {
			addFunc(text[m[2]:m[3]], m[0])
		}
	}
	for _, re := range reactComponentRegexClass {
		for _, m := range re.FindAllStringSubmatchIndex(text, -1) {
			addClass(text[m[2]:m[3]], m[0])
		}
	}

	// Hooks
	seenHooks := map[string]bool{}
	for _, re := range reactHookRegexes {
		for _, m := range re.FindAllStringSubmatchIndex(text, -1) {
			name := text[m[2]:m[3]]
			if seenHooks[name] {
				continue
			}
			seenHooks[name] = true
			n := base.CreateComponentNode(propReact, fp, "hook", name, model.NodeHook, base.LineAt(text, m[0]))
			n.Confidence = base.RegexDetectorDefaultConfidence
			nodes = append(nodes, n)
		}
	}

	// RENDERS edges: scope JSX tag search to each component's body section.
	sort.Slice(compEntries, func(i, j int) bool {
		return compEntries[i].matchStart < compEntries[j].matchStart
	})
	for i, comp := range compEntries {
		bodyStart := comp.matchStart
		bodyEnd := len(text)
		if i+1 < len(compEntries) {
			bodyEnd = compEntries[i+1].matchStart
		}
		body := text[bodyStart:bodyEnd]
		childSet := map[string]bool{}
		for _, jm := range reactJSXTag.FindAllStringSubmatch(body, -1) {
			tag := jm[1]
			if tag != comp.name {
				childSet[tag] = true
			}
		}
		children := make([]string, 0, len(childSet))
		for c := range childSet {
			children = append(children, c)
		}
		sort.Strings(children)
		for _, child := range children {
			e := model.NewCodeEdge(comp.sourceID+":renders:"+child, model.EdgeRenders, comp.sourceID, child)
			e.Confidence = base.RegexDetectorDefaultConfidence
			edges = append(edges, e)
		}
	}
	return detector.ResultOf(nodes, edges)
}
