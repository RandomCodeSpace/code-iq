package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// ClassHierarchyDetector mirrors Java ClassHierarchyDetector regex tier.
// Detects classes/interfaces/enums/annotation-types and their EXTENDS/IMPLEMENTS edges.
type ClassHierarchyDetector struct{}

func NewClassHierarchyDetector() *ClassHierarchyDetector { return &ClassHierarchyDetector{} }

func (ClassHierarchyDetector) Name() string                 { return "java.class_hierarchy" }
func (ClassHierarchyDetector) SupportedLanguages() []string { return []string{"java"} }
func (ClassHierarchyDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewClassHierarchyDetector()) }

var (
	chClassDeclRE = regexp.MustCompile(
		`(public\s+|protected\s+|private\s+)?(abstract\s+)?(final\s+)?class\s+(\w+)(?:\s+extends\s+(\w+))?(?:\s+implements\s+([\w,\s]+))?`,
	)
	chInterfaceDeclRE = regexp.MustCompile(
		`(public\s+|protected\s+|private\s+)?interface\s+(\w+)(?:\s+extends\s+([\w,\s]+))?`,
	)
	chEnumDeclRE = regexp.MustCompile(
		`(public\s+|protected\s+|private\s+)?enum\s+(\w+)(?:\s+implements\s+([\w,\s]+))?`,
	)
	chAnnotationDeclRE = regexp.MustCompile(`(public\s+|protected\s+|private\s+)?@interface\s+(\w+)`)
)

func (d ClassHierarchyDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	for i, line := range lines {
		// Annotation type FIRST — `@interface` would otherwise also match
		// chInterfaceDeclRE because Go RE2 doesn't anchor `^` for `interface`.
		if am := chAnnotationDeclRE.FindStringSubmatch(line); am != nil {
			visibility := chParseVisibility(am[1])
			name := am[2]
			nodeID := ctx.FilePath + ":" + name
			n := model.NewCodeNode(nodeID, model.NodeAnnotationType, name)
			n.FQN = name
			n.FilePath = ctx.FilePath
			n.LineStart = i + 1
			n.Source = "ClassHierarchyDetector"
			n.Properties["visibility"] = visibility
			n.Properties["is_abstract"] = false
			n.Properties["is_final"] = false
			nodes = append(nodes, n)
			continue
		}

		// Class — try first since `class` appears in interface/enum too — but the
		// patterns require the literal keyword `class` so order is fine.
		if cm := chClassDeclRE.FindStringSubmatch(line); cm != nil {
			visibility := chParseVisibility(cm[1])
			isAbstract := cm[2] != ""
			isFinal := cm[3] != ""
			name := cm[4]
			superclass := cm[5]
			interfaces := chParseTypeList(cm[6])

			nodeID := ctx.FilePath + ":" + name
			kind := model.NodeClass
			if isAbstract {
				kind = model.NodeAbstractClass
			}
			n := model.NewCodeNode(nodeID, kind, name)
			n.FQN = name
			n.FilePath = ctx.FilePath
			n.LineStart = i + 1
			n.Source = "ClassHierarchyDetector"
			n.Properties["visibility"] = visibility
			n.Properties["is_abstract"] = isAbstract
			n.Properties["is_final"] = isFinal
			if superclass != "" {
				n.Properties["superclass"] = superclass
			}
			if len(interfaces) > 0 {
				n.Properties["interfaces"] = interfaces
			}
			nodes = append(nodes, n)

			if superclass != "" {
				edges = append(edges, model.NewCodeEdge(nodeID+"->extends->*:"+superclass, model.EdgeExtends, nodeID, "*:"+superclass))
			}
			for _, iface := range interfaces {
				edges = append(edges, model.NewCodeEdge(nodeID+"->implements->*:"+iface, model.EdgeImplements, nodeID, "*:"+iface))
			}
			continue
		}

		// Interface
		if im := chInterfaceDeclRE.FindStringSubmatch(line); im != nil {
			visibility := chParseVisibility(im[1])
			name := im[2]
			extended := chParseTypeList(im[3])

			nodeID := ctx.FilePath + ":" + name
			n := model.NewCodeNode(nodeID, model.NodeInterface, name)
			n.FQN = name
			n.FilePath = ctx.FilePath
			n.LineStart = i + 1
			n.Source = "ClassHierarchyDetector"
			n.Properties["visibility"] = visibility
			n.Properties["is_abstract"] = false
			n.Properties["is_final"] = false
			if len(extended) > 0 {
				n.Properties["interfaces"] = extended
			}
			nodes = append(nodes, n)

			for _, ext := range extended {
				edges = append(edges, model.NewCodeEdge(nodeID+"->extends->*:"+ext, model.EdgeExtends, nodeID, "*:"+ext))
			}
			continue
		}

		// Enum
		if em := chEnumDeclRE.FindStringSubmatch(line); em != nil {
			visibility := chParseVisibility(em[1])
			name := em[2]
			interfaces := chParseTypeList(em[3])

			nodeID := ctx.FilePath + ":" + name
			n := model.NewCodeNode(nodeID, model.NodeEnum, name)
			n.FQN = name
			n.FilePath = ctx.FilePath
			n.LineStart = i + 1
			n.Source = "ClassHierarchyDetector"
			n.Properties["visibility"] = visibility
			n.Properties["is_abstract"] = false
			n.Properties["is_final"] = false
			if len(interfaces) > 0 {
				n.Properties["interfaces"] = interfaces
			}
			nodes = append(nodes, n)
			for _, iface := range interfaces {
				edges = append(edges, model.NewCodeEdge(nodeID+"->implements->*:"+iface, model.EdgeImplements, nodeID, "*:"+iface))
			}
			continue
		}

	}

	return detector.ResultOf(nodes, edges)
}

func chParseVisibility(modifier string) string {
	if modifier == "" {
		return "package-private"
	}
	trimmed := strings.TrimSpace(modifier)
	switch trimmed {
	case "public", "protected", "private":
		return trimmed
	}
	return "package-private"
}

func chParseTypeList(typeList string) []string {
	if strings.TrimSpace(typeList) == "" {
		return nil
	}
	var result []string
	for _, t := range strings.Split(typeList, ",") {
		trimmed := strings.TrimSpace(t)
		if trimmed != "" {
			result = append(result, trimmed)
		}
	}
	return result
}
