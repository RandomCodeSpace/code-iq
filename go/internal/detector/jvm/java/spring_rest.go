package java

import (
	"fmt"
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// SpringRestDetector detects Spring MVC REST endpoints from mapping annotations.
// Phase 1 ships the regex-fallback path only; tree-sitter AST refinement lands
// in phase 4.
type SpringRestDetector struct{}

func NewSpringRestDetector() *SpringRestDetector { return &SpringRestDetector{} }

func (SpringRestDetector) Name() string                        { return "spring_rest" }
func (SpringRestDetector) SupportedLanguages() []string        { return []string{"java"} }
func (SpringRestDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewSpringRestDetector()) }

// Patterns mirror SpringRestDetector.java's regex fallback.
var (
	classRE   = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
	mappingRE = regexp.MustCompile(
		`@(RequestMapping|GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping)` +
			`\s*(?:\(([^)]*)\))?`)
	valueRE      = regexp.MustCompile(`(?:value\s*=\s*|path\s*=\s*)?\{?\s*"([^"]*)"`)
	methodAttrRE = regexp.MustCompile(`method\s*=\s*RequestMethod\.(\w+)`)
	producesRE   = regexp.MustCompile(`produces\s*=\s*\{?\s*"([^"]*)"`)
	consumesRE   = regexp.MustCompile(`consumes\s*=\s*\{?\s*"([^"]*)"`)
	javaMethodRE = regexp.MustCompile(
		`(?:public|protected|private)?\s*(?:static\s+)?(?:[\w<>\[\],\s]+)\s+(\w+)\s*\(`)
	nonEndpointRE = regexp.MustCompile(`@(ModelAttribute|InitBinder|ExceptionHandler)\b`)
)

var mappingHTTPMethod = map[string]string{
	"GetMapping":    "GET",
	"PostMapping":   "POST",
	"PutMapping":    "PUT",
	"DeleteMapping": "DELETE",
	"PatchMapping":  "PATCH",
}

func (d SpringRestDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if !strings.Contains(text, "Mapping") {
		return detector.EmptyResult()
	}

	cm := classRE.FindStringSubmatchIndex(text)
	className := ""
	if cm != nil {
		className = text[cm[2]:cm[3]]
	}
	if className == "" {
		className = "Unknown"
	}

	// Class-level base path from a class @RequestMapping (if any). Heuristic:
	// the first mapping annotation in the file that immediately precedes the
	// "class" keyword.
	basePath := ""
	classIdx := -1
	if cm != nil {
		classIdx = cm[0]
	}
	for _, m := range mappingRE.FindAllStringSubmatchIndex(text, -1) {
		if classIdx >= 0 && m[0] < classIdx {
			args := ""
			if m[4] >= 0 {
				args = text[m[4]:m[5]]
			}
			if v := valueRE.FindStringSubmatch(args); len(v) >= 2 {
				basePath = v[1]
			}
		}
	}

	var nodes []*model.CodeNode
	matches := mappingRE.FindAllStringSubmatchIndex(text, -1)
	for _, m := range matches {
		annotation := text[m[2]:m[3]]
		args := ""
		if m[4] >= 0 {
			args = text[m[4]:m[5]]
		}

		// Skip class-level mappings (only emit endpoints for method-level).
		if classIdx >= 0 && m[0] < classIdx {
			continue
		}

		// Window of text immediately following the mapping annotation, used to
		// find the method name and to detect non-endpoint annotation markers.
		end := m[1]
		windowEnd := end + 400
		if windowEnd > len(text) {
			windowEnd = len(text)
		}
		window := text[end:windowEnd]
		mmIdx := javaMethodRE.FindStringSubmatchIndex(window)
		if mmIdx == nil {
			continue
		}
		// Restrict the non-endpoint annotation check to the text between this
		// mapping and the method signature — without this bound, the check
		// would pick up the NEXT method's annotations.
		if nonEndpointRE.MatchString(window[:mmIdx[0]]) {
			continue
		}
		methodName := window[mmIdx[2]:mmIdx[3]]

		// Skip language keywords that javaMethodRE may capture (`if`, `for`, etc.)
		if isJavaKeyword(methodName) {
			continue
		}

		path := ""
		if v := valueRE.FindStringSubmatch(args); len(v) >= 2 {
			path = v[1]
		}
		fullPath := joinPath(basePath, path)

		httpMethod := mappingHTTPMethod[annotation]
		if httpMethod == "" {
			// @RequestMapping with explicit method attribute, else default "GET".
			httpMethod = "GET"
			if mt := methodAttrRE.FindStringSubmatch(args); len(mt) >= 2 {
				httpMethod = mt[1]
			}
		}

		id := fmt.Sprintf("%s:%s:%s:%s", ctx.FilePath, className, methodName, httpMethod)
		n := model.NewCodeNode(id, model.NodeEndpoint, methodName)
		n.FilePath = ctx.FilePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "SpringRestDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["framework"] = "spring_boot"
		n.Properties["http_method"] = httpMethod
		n.Properties["path"] = fullPath
		n.Properties["method"] = methodName
		if p := producesRE.FindStringSubmatch(args); len(p) >= 2 {
			n.Properties["produces"] = p[1]
		}
		if c := consumesRE.FindStringSubmatch(args); len(c) >= 2 {
			n.Properties["consumes"] = c[1]
		}
		nodes = append(nodes, n)
	}
	return detector.ResultOf(nodes, nil)
}

func joinPath(basePath, sub string) string {
	if basePath == "" {
		return sub
	}
	if sub == "" {
		return basePath
	}
	if strings.HasSuffix(basePath, "/") && strings.HasPrefix(sub, "/") {
		return basePath + sub[1:]
	}
	if !strings.HasSuffix(basePath, "/") && !strings.HasPrefix(sub, "/") {
		return basePath + "/" + sub
	}
	return basePath + sub
}

func isJavaKeyword(s string) bool {
	switch s {
	case "if", "for", "while", "switch", "return", "throw", "try", "catch",
		"new", "class", "do", "else", "synchronized", "static":
		return true
	}
	return false
}
