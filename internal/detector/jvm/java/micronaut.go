package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// MicronautDetector mirrors Java MicronautDetector. Detects:
//   - @Controller("/path") classes
//   - HTTP method annotations @Get/@Post/@Put/@Delete
//   - Bean scopes (@Singleton/@Prototype/@Infrastructure)
//   - @Client("...") + @Inject
//   - @Scheduled(fixedRate = "...")
//   - @EventListener
//
// REQUIRES io.micronaut import OR @Client (Micronaut-specific) discriminator.
type MicronautDetector struct{}

func NewMicronautDetector() *MicronautDetector { return &MicronautDetector{} }

func (MicronautDetector) Name() string                 { return "micronaut" }
func (MicronautDetector) SupportedLanguages() []string { return []string{"java"} }
func (MicronautDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewMicronautDetector()) }

var (
	micControllerRE = regexp.MustCompile(`@Controller\s*\(\s*"([^"]*)"`)
	// @Get/@Post/@Put/@Delete but NOT @GetMapping (Spring). Go regex doesn't
	// support lookahead `(?!Mapping)`, so we match the suffix `\b` and then
	// reject the match in code if the annotation is followed by "Mapping".
	micHTTPMethodRE = regexp.MustCompile(`@(Get|Post|Put|Delete)([A-Za-z]?)\s*(?:\(\s*"([^"]*)")?`)
	micBeanScopeRE  = regexp.MustCompile(`@(Singleton|Prototype|Infrastructure)\b`)
	micClientRE     = regexp.MustCompile(`@Client\s*\(\s*"([^"]*)"`)
	micInjectRE     = regexp.MustCompile(`@Inject\b`)
	micScheduledRE  = regexp.MustCompile(`@Scheduled\s*\(\s*fixedRate\s*=\s*"([^"]+)"`)
	micEventListRE  = regexp.MustCompile(`@EventListener\b`)
	micClassRE      = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
	micJavaMethodRE = regexp.MustCompile(
		`(?:public|protected|private)?\s*(?:static\s+)?(?:[\w<>\[\],\s]+)\s+(\w+)\s*\(`,
	)
)

func (d MicronautDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}

	hasMicronaut := strings.Contains(text, "io.micronaut") || strings.Contains(text, "@Client")
	if !hasMicronaut {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "@Controller") && !strings.Contains(text, "@Get") &&
		!strings.Contains(text, "@Post") && !strings.Contains(text, "@Put") &&
		!strings.Contains(text, "@Delete") && !strings.Contains(text, "@Singleton") &&
		!strings.Contains(text, "@Prototype") && !strings.Contains(text, "@Infrastructure") &&
		!strings.Contains(text, "@Client") && !strings.Contains(text, "@Inject") &&
		!strings.Contains(text, "@Scheduled") && !strings.Contains(text, "@EventListener") &&
		!strings.Contains(text, "io.micronaut") {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	// First class + class-level @Controller path
	var className, controllerPath string
	for i, line := range lines {
		if m := micClassRE.FindStringSubmatch(line); m != nil {
			className = m[1]
			// Look up to 5 lines back for @Controller("path")
			for j := max0(i - 5); j < i; j++ {
				if pm := micControllerRE.FindStringSubmatch(lines[j]); pm != nil {
					controllerPath = strings.TrimRight(pm[1], "/")
					break
				}
			}
			break
		}
	}

	classNodeID := ctx.FilePath
	if className != "" {
		classNodeID = ctx.FilePath + ":" + className
	}

	if controllerPath != "" && className != "" {
		ctrlNode := model.NewCodeNode(
			"micronaut:"+ctx.FilePath+":controller:"+className,
			model.NodeClass,
			"@Controller("+controllerPath+") "+className,
		)
		ctrlNode.FQN = className
		ctrlNode.FilePath = ctx.FilePath
		ctrlNode.LineStart = 1
		ctrlNode.Annotations = append(ctrlNode.Annotations, "@Controller")
		ctrlNode.Source = "MicronautDetector"
		ctrlNode.Properties["framework"] = "micronaut"
		ctrlNode.Properties["path"] = controllerPath
		nodes = append(nodes, ctrlNode)
	}

	for i, line := range lines {
		lineno := i + 1

		// HTTP methods
		if m := micHTTPMethodRE.FindStringSubmatch(line); m != nil {
			// reject @GetMapping (Spring) — the trailing capture group catches `[A-Za-z]`.
			// In Java, `(?!Mapping)` negative lookahead; here we filter on m[2].
			if m[2] != "" {
				// non-empty letter after method name → looks like @GetMapping/@PostMapping
				if strings.HasPrefix(line[strings.Index(line, "@"+m[1]):], "@"+m[1]+"Mapping") {
					continue
				}
			}
			httpMethod := strings.ToUpper(m[1])
			methodPath := ""
			if len(m) >= 4 {
				methodPath = m[3]
			}
			var fullPath string
			switch {
			case controllerPath != "":
				if methodPath != "" {
					fullPath = controllerPath + "/" + strings.TrimLeft(methodPath, "/")
				} else {
					fullPath = controllerPath
				}
			case methodPath != "":
				fullPath = "/" + strings.TrimLeft(methodPath, "/")
			default:
				fullPath = "/"
			}
			if !strings.HasPrefix(fullPath, "/") {
				fullPath = "/" + fullPath
			}

			var methodName string
			for k := i + 1; k < min0(i+5, len(lines)); k++ {
				if mm := micJavaMethodRE.FindStringSubmatch(lines[k]); mm != nil {
					methodName = mm[1]
					break
				}
			}

			nodeID := "micronaut:" + ctx.FilePath + ":endpoint:" + httpMethod + ":" + fullPath + ":" + itoaQ(lineno)
			n := model.NewCodeNode(nodeID, model.NodeEndpoint, httpMethod+" "+fullPath)
			if className != "" && methodName != "" {
				n.FQN = className + "." + methodName
			} else {
				n.FQN = className
			}
			n.FilePath = ctx.FilePath
			n.LineStart = lineno
			n.Annotations = append(n.Annotations, "@"+m[1])
			n.Source = "MicronautDetector"
			n.Properties["framework"] = "micronaut"
			n.Properties["http_method"] = httpMethod
			n.Properties["path"] = fullPath
			nodes = append(nodes, n)

			edges = append(edges, model.NewCodeEdge(classNodeID+"->exposes->"+nodeID, model.EdgeExposes, classNodeID, nodeID))
		}

		if m := micBeanScopeRE.FindStringSubmatch(line); m != nil {
			scope := m[1]
			nodeID := "micronaut:" + ctx.FilePath + ":scope_" + strings.ToLower(scope) + ":" + itoaQ(lineno)
			n := model.NewCodeNode(nodeID, model.NodeMiddleware, "@"+scope+" (bean scope)")
			if className != "" {
				n.FQN = className + "." + scope
			} else {
				n.FQN = scope
			}
			n.FilePath = ctx.FilePath
			n.LineStart = lineno
			n.Annotations = append(n.Annotations, "@"+scope)
			n.Source = "MicronautDetector"
			n.Properties["framework"] = "micronaut"
			n.Properties["bean_scope"] = scope
			nodes = append(nodes, n)
		}

		if m := micClientRE.FindStringSubmatch(line); m != nil {
			clientTarget := m[1]
			nodeID := "micronaut:" + ctx.FilePath + ":client:" + itoaQ(lineno)
			n := model.NewCodeNode(nodeID, model.NodeClass, "@Client("+clientTarget+")")
			n.FQN = clientTarget
			n.FilePath = ctx.FilePath
			n.LineStart = lineno
			n.Annotations = append(n.Annotations, "@Client")
			n.Source = "MicronautDetector"
			n.Properties["framework"] = "micronaut"
			n.Properties["client_target"] = clientTarget
			nodes = append(nodes, n)
			edges = append(edges, model.NewCodeEdge(classNodeID+"->depends_on->"+nodeID, model.EdgeDependsOn, classNodeID, nodeID))
		}

		if micInjectRE.MatchString(line) {
			nodeID := "micronaut:" + ctx.FilePath + ":inject:" + itoaQ(lineno)
			n := model.NewCodeNode(nodeID, model.NodeMiddleware, "@Inject")
			if className != "" {
				n.FQN = className + ".inject"
			} else {
				n.FQN = "inject"
			}
			n.FilePath = ctx.FilePath
			n.LineStart = lineno
			n.Annotations = append(n.Annotations, "@Inject")
			n.Source = "MicronautDetector"
			n.Properties["framework"] = "micronaut"
			nodes = append(nodes, n)
		}

		if m := micScheduledRE.FindStringSubmatch(line); m != nil {
			rate := m[1]
			nodeID := "micronaut:" + ctx.FilePath + ":scheduled:" + itoaQ(lineno)
			n := model.NewCodeNode(nodeID, model.NodeEvent, "@Scheduled(fixedRate="+rate+")")
			if className != "" {
				n.FQN = className + ".scheduled"
			} else {
				n.FQN = "scheduled"
			}
			n.FilePath = ctx.FilePath
			n.LineStart = lineno
			n.Annotations = append(n.Annotations, "@Scheduled")
			n.Source = "MicronautDetector"
			n.Properties["framework"] = "micronaut"
			n.Properties["fixed_rate"] = rate
			nodes = append(nodes, n)
		}

		if micEventListRE.MatchString(line) {
			nodeID := "micronaut:" + ctx.FilePath + ":event_listener:" + itoaQ(lineno)
			n := model.NewCodeNode(nodeID, model.NodeEvent, "@EventListener")
			if className != "" {
				n.FQN = className + ".eventListener"
			} else {
				n.FQN = "eventListener"
			}
			n.FilePath = ctx.FilePath
			n.LineStart = lineno
			n.Annotations = append(n.Annotations, "@EventListener")
			n.Source = "MicronautDetector"
			n.Properties["framework"] = "micronaut"
			nodes = append(nodes, n)
		}
	}

	return detector.ResultOf(nodes, edges)
}

func max0(i int) int {
	if i < 0 {
		return 0
	}
	return i
}

func min0(a, b int) int {
	if a < b {
		return a
	}
	return b
}
