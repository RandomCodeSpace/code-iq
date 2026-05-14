package typescript

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// TypeORMEntityDetector ports
// io.github.randomcodespace.iq.detector.typescript.TypeORMEntityDetector.
type TypeORMEntityDetector struct{}

func NewTypeORMEntityDetector() *TypeORMEntityDetector { return &TypeORMEntityDetector{} }

func (TypeORMEntityDetector) Name() string                 { return "typescript.typeorm_entities" }
func (TypeORMEntityDetector) SupportedLanguages() []string { return []string{"typescript", "javascript"} }
func (TypeORMEntityDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewTypeORMEntityDetector()) }

var (
	typeormEntityRE = regexp.MustCompile(
		`@Entity\(\s*['"` + "`" + `]?(\w*)['"` + "`" + `]?\s*\)\s*\n\s*(?:export\s+)?class\s+(\w+)`)
	typeormColumnRE   = regexp.MustCompile(`@Column\([^)]*\)\s*\n?\s*(\w+)\s*[!?]?\s*:\s*(\w+)`)
	typeormRelationRE = regexp.MustCompile(`@(ManyToOne|OneToMany|ManyToMany|OneToOne)\(\s*\(\)\s*=>\s*(\w+)`)
)

func (d TypeORMEntityDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName

	for _, m := range typeormEntityRE.FindAllStringSubmatchIndex(text, -1) {
		tableName := text[m[2]:m[3]]
		className := text[m[4]:m[5]]
		if tableName == "" {
			tableName = strings.ToLower(className) + "s"
		}
		line := base.FindLineNumber(text, m[0])

		// Find class body by brace matching.
		classStart := m[1]
		braceCount := 0
		classEnd := len(text)
		for i := classStart; i < len(text); i++ {
			switch text[i] {
			case '{':
				braceCount++
			case '}':
				braceCount--
				if braceCount == 0 {
					classEnd = i
					i = len(text) // break outer
				}
			}
		}
		classBody := text[classStart:classEnd]

		var columns []string
		for _, c := range typeormColumnRE.FindAllStringSubmatch(classBody, -1) {
			columns = append(columns, c[1])
		}

		nodeID := "entity:" + moduleName + ":" + className
		n := model.NewCodeNode(nodeID, model.NodeEntity, className)
		n.FQN = filePath + "::" + className
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "TypeORMEntityDetector"
		n.Confidence = model.ConfidenceLexical
		n.Annotations = append(n.Annotations, "@Entity")
		n.Properties["table_name"] = tableName
		n.Properties["columns"] = columns
		n.Properties["framework"] = "typeorm"
		nodes = append(nodes, n)

		for _, rm := range typeormRelationRE.FindAllStringSubmatch(classBody, -1) {
			relType := rm[1]
			target := rm[2]
			targetID := "entity:" + moduleName + ":" + target
			e := model.NewCodeEdge(nodeID+"->"+relType+"->"+targetID, model.EdgeMapsTo, nodeID, targetID)
			e.Source = "TypeORMEntityDetector"
			e.Confidence = model.ConfidenceLexical
			e.Properties["relation_type"] = relType
			edges = append(edges, e)
		}
	}

	return detector.ResultOf(nodes, edges)
}
