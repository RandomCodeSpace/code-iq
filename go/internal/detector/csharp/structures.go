package csharp

import (
	"regexp"
	"strconv"
	"strings"
	"unicode"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// StructuresDetector detects C# namespaces, classes, interfaces, enums, using
// imports, and MVC controller endpoints (Route + HttpGet/Post/...). Mirrors
// Java CSharpStructuresDetector.
type StructuresDetector struct{}

func NewStructuresDetector() *StructuresDetector { return &StructuresDetector{} }

func (StructuresDetector) Name() string                        { return "csharp_structures" }
func (StructuresDetector) SupportedLanguages() []string        { return []string{"csharp"} }
func (StructuresDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewStructuresDetector()) }

var (
	csharpClassRE     = regexp.MustCompile(`(?:public|internal|private|protected)?\s*(?:abstract|static|sealed|partial)?\s*class\s+(\w+)(?:\s*<[^>]+>)?(?:\s*:\s*([^{]+))?`)
	csharpInterfaceRE = regexp.MustCompile(`(?:public|internal)?\s*interface\s+(\w+)(?:\s*<[^>]+>)?(?:\s*:\s*([^{]+))?`)
	csharpEnumRE      = regexp.MustCompile(`(?:public|internal)?\s*enum\s+(\w+)`)
	csharpNamespaceRE = regexp.MustCompile(`namespace\s+([\w.]+)`)
	csharpUsingRE     = regexp.MustCompile(`(?m)^\s*using\s+([\w.]+)\s*;`)
	csharpHttpAttrRE  = regexp.MustCompile(`\[(Http(?:Get|Post|Put|Delete|Patch))\s*(?:\("([^"]*)"\))?\]`)
	csharpRouteRE     = regexp.MustCompile(`\[Route\("([^"]*)"\)\]`)
	csharpMethodRE    = regexp.MustCompile(`(?:public|protected|private|internal)\s+(?:static\s+|virtual\s+|override\s+|async\s+|abstract\s+)*(?:[\w<>\[\]?,\s]+)\s+(\w+)\s*\(`)
	csharpGenericRE   = regexp.MustCompile(`<[^>]*>`)
	csharpSlashTrimRE = regexp.MustCompile(`^/+|/+$`)
	csharpLeadSlashRE = regexp.MustCompile(`^/+`)
)

var csharpSkipMethodNames = map[string]bool{
	"if": true, "for": true, "while": true, "switch": true,
	"catch": true, "using": true, "return": true, "new": true, "class": true,
}

func (d StructuresDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	lines := strings.Split(text, "\n")

	// Namespace
	var namespace string
	if m := csharpNamespaceRE.FindStringSubmatchIndex(text); len(m) >= 4 {
		namespace = text[m[2]:m[3]]
		n := model.NewCodeNode(filePath+":namespace:"+namespace, model.NodeModule, namespace)
		n.FQN = namespace
		n.FilePath = filePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "CSharpStructuresDetector"
		nodes = append(nodes, n)
	}

	// Using statements
	for _, m := range csharpUsingRE.FindAllStringSubmatchIndex(text, -1) {
		imp := text[m[2]:m[3]]
		e := model.NewCodeEdge(filePath+":imports:"+imp, model.EdgeImports, filePath, imp)
		e.Source = "CSharpStructuresDetector"
		edges = append(edges, e)
	}

	// Classes — also track the class route for endpoint detection
	var classRoute string
	for _, m := range csharpClassRE.FindAllStringSubmatchIndex(text, -1) {
		className := text[m[2]:m[3]]
		var baseStr string
		if m[4] >= 0 {
			baseStr = text[m[4]:m[5]]
		}
		lineNum := base.FindLineNumber(text, m[0])
		// Examine a window around the class match to spot "abstract"
		start := m[0] - 60
		if start < 0 {
			start = 0
		}
		matchText := text[start:m[1]]
		isAbstract := strings.Contains(matchText, "abstract")
		kind := model.NodeClass
		if isAbstract {
			kind = model.NodeAbstractClass
		}
		fqn := className
		if namespace != "" {
			fqn = namespace + "." + className
		}
		nodeID := filePath + ":" + className

		n := model.NewCodeNode(nodeID, kind, className)
		n.FQN = fqn
		n.FilePath = filePath
		n.LineStart = lineNum
		n.Source = "CSharpStructuresDetector"
		if isAbstract {
			n.Properties["is_abstract"] = true
		}

		baseClass, ifaceList := parseCSharpBaseTypes(baseStr)
		if baseClass != "" {
			n.Properties["base_class"] = baseClass
			e := model.NewCodeEdge(
				nodeID+":extends:"+baseClass, model.EdgeExtends, nodeID, "*:"+baseClass,
			)
			e.Source = "CSharpStructuresDetector"
			edges = append(edges, e)
		}
		if len(ifaceList) > 0 {
			n.Properties["interfaces"] = ifaceList
			for _, iface := range ifaceList {
				e := model.NewCodeEdge(
					nodeID+":implements:"+iface, model.EdgeImplements, nodeID, "*:"+iface,
				)
				e.Source = "CSharpStructuresDetector"
				edges = append(edges, e)
			}
		}
		nodes = append(nodes, n)

		// Check 5 lines above class for [Route(...)]
		classLineIdx := lineNum - 1
		startLine := classLineIdx - 5
		if startLine < 0 {
			startLine = 0
		}
		for j := startLine; j < classLineIdx && j < len(lines); j++ {
			if rm := csharpRouteRE.FindStringSubmatch(lines[j]); len(rm) >= 2 {
				route := rm[1]
				ctrl := strings.TrimSuffix(className, "Controller")
				classRoute = strings.ReplaceAll(route, "[controller]", ctrl)
				break
			}
		}
	}

	// Interfaces
	for _, m := range csharpInterfaceRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		fqn := name
		if namespace != "" {
			fqn = namespace + "." + name
		}
		n := model.NewCodeNode(filePath+":"+name, model.NodeInterface, name)
		n.FQN = fqn
		n.FilePath = filePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "CSharpStructuresDetector"
		nodes = append(nodes, n)
	}

	// Enums
	for _, m := range csharpEnumRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		fqn := name
		if namespace != "" {
			fqn = namespace + "." + name
		}
		n := model.NewCodeNode(filePath+":"+name, model.NodeEnum, name)
		n.FQN = fqn
		n.FilePath = filePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "CSharpStructuresDetector"
		nodes = append(nodes, n)
	}

	// HTTP endpoints (scan line-by-line, looking 5 lines up for HttpXxx attrs)
	for i, line := range lines {
		mm := csharpMethodRE.FindStringSubmatch(line)
		if len(mm) < 2 {
			continue
		}
		methodName := mm[1]
		if csharpSkipMethodNames[methodName] {
			continue
		}
		var httpMethodStr, httpPath string
		startLine := i - 5
		if startLine < 0 {
			startLine = 0
		}
		for j := startLine; j < i; j++ {
			if hm := csharpHttpAttrRE.FindStringSubmatch(lines[j]); len(hm) >= 2 {
				httpMethodStr = strings.ToUpper(strings.TrimPrefix(hm[1], "Http"))
				if len(hm) >= 3 {
					httpPath = hm[2]
				}
				break
			}
		}
		if httpMethodStr == "" {
			continue
		}

		fullPath := composePath(classRoute, httpPath)
		moduleName := ctx.ModuleName
		fqn := methodName
		if namespace != "" {
			fqn = namespace + "." + methodName
		}
		n := model.NewCodeNode(
			"endpoint:"+moduleName+":"+methodName+":"+httpMethodStr+":"+fullPath,
			model.NodeEndpoint, httpMethodStr+" "+fullPath,
		)
		n.FQN = fqn
		n.FilePath = filePath
		n.LineStart = i + 1
		n.Source = "CSharpStructuresDetector"
		n.Properties["http_method"] = httpMethodStr
		n.Properties["path"] = fullPath
		nodes = append(nodes, n)
	}

	_ = strconv.Itoa // (in case future ID building needs it)
	return detector.ResultOf(nodes, edges)
}

// composePath joins a class route with a method-level path. Matches the Java
// side's trim/normalize behaviour.
func composePath(classRoute, path string) string {
	if classRoute != "" {
		trimmed := csharpSlashTrimRE.ReplaceAllString(classRoute, "")
		full := "/" + trimmed
		if path != "" {
			full = full + "/" + csharpLeadSlashRE.ReplaceAllString(path, "")
		}
		return full
	}
	if path != "" {
		return "/" + csharpLeadSlashRE.ReplaceAllString(path, "")
	}
	return "/"
}

// parseCSharpBaseTypes splits the comma-separated base-type list into a single
// base class (non-interface) and a list of interfaces. Interfaces are
// identified by the convention "IXxx" — second char is uppercase, first is 'I'.
func parseCSharpBaseTypes(baseStr string) (string, []string) {
	if strings.TrimSpace(baseStr) == "" {
		return "", nil
	}
	parts := strings.Split(baseStr, ",")
	var baseClass string
	var interfaces []string
	for _, p := range parts {
		clean := strings.TrimSpace(csharpGenericRE.ReplaceAllString(p, ""))
		if clean == "" {
			continue
		}
		if len(clean) >= 2 && clean[0] == 'I' && unicode.IsUpper(rune(clean[1])) {
			interfaces = append(interfaces, clean)
		} else if baseClass == "" {
			baseClass = clean
		} else {
			interfaces = append(interfaces, clean)
		}
	}
	return baseClass, interfaces
}
