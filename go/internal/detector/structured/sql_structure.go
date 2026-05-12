package structured

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// SqlStructureDetector mirrors Java SqlStructureDetector. Regex-based scan
// for CREATE TABLE / VIEW / INDEX / PROCEDURE plus REFERENCES (FK) edges
// from the most recently seen table.
type SqlStructureDetector struct{}

func NewSqlStructureDetector() *SqlStructureDetector { return &SqlStructureDetector{} }

func (SqlStructureDetector) Name() string                        { return "sql_structure" }
func (SqlStructureDetector) SupportedLanguages() []string        { return []string{"sql"} }
func (SqlStructureDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewSqlStructureDetector()) }

var (
	sqlTableRE     = regexp.MustCompile(`(?i)CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:\w+\.)?(\w+)`)
	sqlViewRE      = regexp.MustCompile(`(?i)CREATE\s+(?:OR\s+REPLACE\s+)?VIEW\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:\w+\.)?(\w+)`)
	sqlIndexRE     = regexp.MustCompile(`(?i)CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:\w+\.)?(\w+)`)
	sqlProcedureRE = regexp.MustCompile(`(?i)CREATE\s+(?:OR\s+REPLACE\s+)?PROCEDURE\s+(?:\w+\.)?(\w+)`)
	sqlFKRE        = regexp.MustCompile(`(?i)REFERENCES\s+(?:\w+\.)?(\w+)`)
)

func (d SqlStructureDetector) Detect(ctx *detector.Context) *detector.Result {
	content := ctx.Content
	if content == "" {
		return detector.EmptyResult()
	}
	fp := ctx.FilePath
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}
	currentTableID := ""

	lines := strings.Split(content, "\n")
	for i, line := range lines {
		lineNum := i + 1
		// Tables
		if m := sqlTableRE.FindStringSubmatch(line); m != nil {
			name := m[1]
			currentTableID = "sql:" + fp + ":table:" + name
			n := model.NewCodeNode(currentTableID, model.NodeEntity, name)
			n.FQN = name
			n.Module = ctx.ModuleName
			n.FilePath = fp
			n.LineStart = lineNum
			n.Confidence = base.RegexDetectorDefaultConfidence
			n.Properties["entity_type"] = "table"
			nodes = append(nodes, n)
			continue
		}
		// Views
		if m := sqlViewRE.FindStringSubmatch(line); m != nil {
			name := m[1]
			n := model.NewCodeNode("sql:"+fp+":view:"+name, model.NodeEntity, name)
			n.FQN = name
			n.Module = ctx.ModuleName
			n.FilePath = fp
			n.LineStart = lineNum
			n.Confidence = base.RegexDetectorDefaultConfidence
			n.Properties["entity_type"] = "view"
			nodes = append(nodes, n)
			currentTableID = ""
			continue
		}
		// Indexes
		if m := sqlIndexRE.FindStringSubmatch(line); m != nil {
			name := m[1]
			n := model.NewCodeNode("sql:"+fp+":index:"+name, model.NodeConfigDefinition, name)
			n.FQN = name
			n.Module = ctx.ModuleName
			n.FilePath = fp
			n.LineStart = lineNum
			n.Confidence = base.RegexDetectorDefaultConfidence
			n.Properties["definition_type"] = "index"
			nodes = append(nodes, n)
			continue
		}
		// Procedures
		if m := sqlProcedureRE.FindStringSubmatch(line); m != nil {
			name := m[1]
			n := model.NewCodeNode("sql:"+fp+":procedure:"+name, model.NodeEntity, name)
			n.FQN = name
			n.Module = ctx.ModuleName
			n.FilePath = fp
			n.LineStart = lineNum
			n.Confidence = base.RegexDetectorDefaultConfidence
			n.Properties["entity_type"] = "procedure"
			nodes = append(nodes, n)
			currentTableID = ""
			continue
		}
		// FKs — only attach if we're inside a CURRENT table.
		if m := sqlFKRE.FindStringSubmatch(line); m != nil && currentTableID != "" {
			refTable := m[1]
			refID := "sql:" + fp + ":table:" + refTable
			e := model.NewCodeEdge(currentTableID+"->"+refID, model.EdgeDependsOn, currentTableID, refID)
			e.Confidence = base.RegexDetectorDefaultConfidence
			e.Properties["relationship"] = "foreign_key"
			edges = append(edges, e)
		}
	}
	return detector.ResultOf(nodes, edges)
}
