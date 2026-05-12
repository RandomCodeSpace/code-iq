package python

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// SQLAlchemyModelDetector ports
// io.github.randomcodespace.iq.detector.python.SQLAlchemyModelDetector.
type SQLAlchemyModelDetector struct{}

func NewSQLAlchemyModelDetector() *SQLAlchemyModelDetector { return &SQLAlchemyModelDetector{} }

func (SQLAlchemyModelDetector) Name() string                 { return "python.sqlalchemy_models" }
func (SQLAlchemyModelDetector) SupportedLanguages() []string { return []string{"python"} }
func (SQLAlchemyModelDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewSQLAlchemyModelDetector()) }

var (
	sqlaModelRE     = regexp.MustCompile(`class\s+(\w+)\(([^)]*(?:Base|Model|DeclarativeBase)[^)]*)\):`)
	sqlaTableNameRE = regexp.MustCompile(`__tablename__\s*=\s*['"](\w+)['"]`)
	sqlaColumnRE    = regexp.MustCompile(`(?m)^\s*(\w+)\s*(?::\s*Mapped\[[^\]]*\])?\s*=\s*(?:Column|mapped_column)\(`)
	sqlaRelationRE  = regexp.MustCompile(`(\w+)\s*(?::\s*Mapped\[[^\]]*\])?\s*=\s*relationship\(\s*['"](\w+)['"]`)
	pyNextClassRE   = regexp.MustCompile(`(?m)^class\s+\w+`)
)

func (d SQLAlchemyModelDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName

	for _, m := range sqlaModelRE.FindAllStringSubmatchIndex(text, -1) {
		className := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])

		// Class body extends from this match to the next top-level class (or EOF).
		classStart := m[0]
		afterEnd := m[1]
		classBody := text[classStart:]
		if next := pyNextClassRE.FindStringIndex(text[afterEnd:]); next != nil {
			classBody = text[classStart : afterEnd+next[0]]
		}

		tableName := ""
		if tm := sqlaTableNameRE.FindStringSubmatch(classBody); len(tm) >= 2 {
			tableName = tm[1]
		}
		if tableName == "" {
			tableName = strings.ToLower(className) + "s"
		}

		var columns []string
		for _, cm := range sqlaColumnRE.FindAllStringSubmatch(classBody, -1) {
			columns = append(columns, cm[1])
		}

		id := "entity:" + moduleName + ":" + className
		n := model.NewCodeNode(id, model.NodeEntity, className)
		n.FQN = filePath + "::" + className
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "SQLAlchemyModelDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["table_name"] = tableName
		n.Properties["columns"] = columns
		n.Properties["framework"] = "sqlalchemy"
		nodes = append(nodes, n)

		for _, rm := range sqlaRelationRE.FindAllStringSubmatch(classBody, -1) {
			target := rm[2]
			targetID := "entity:" + moduleName + ":" + target
			e := model.NewCodeEdge(id+"->maps_to->"+targetID, model.EdgeMapsTo, id, targetID)
			e.Source = "SQLAlchemyModelDetector"
			e.Confidence = model.ConfidenceLexical
			edges = append(edges, e)
		}
	}
	return detector.ResultOf(nodes, edges)
}
