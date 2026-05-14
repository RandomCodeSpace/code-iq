package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// JPAEntityDetector detects JPA / Hibernate @Entity classes and their table
// annotations. Phase 1 = regex path; AST + relationship edges land in phase 4.
type JPAEntityDetector struct{}

func NewJPAEntityDetector() *JPAEntityDetector { return &JPAEntityDetector{} }

func (JPAEntityDetector) Name() string                        { return "jpa_entity" }
func (JPAEntityDetector) SupportedLanguages() []string        { return []string{"java"} }
func (JPAEntityDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewJPAEntityDetector()) }

var (
	jpaTableRE   = regexp.MustCompile(`@Table\s*\(\s*(?:name\s*=\s*)?"(\w+)"`)
	jpaClassRE   = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
	jpaColumnRE  = regexp.MustCompile(`@Column\s*\(([^)]*)\)`)
	jpaColNameRE = regexp.MustCompile(`name\s*=\s*"(\w+)"`)
)

func (d JPAEntityDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if !strings.Contains(text, "@Entity") {
		return detector.EmptyResult()
	}
	cm := jpaClassRE.FindStringSubmatchIndex(text)
	if cm == nil {
		return detector.EmptyResult()
	}
	className := text[cm[2]:cm[3]]
	tableName := strings.ToLower(className)
	if tm := jpaTableRE.FindStringSubmatch(text); len(tm) >= 2 {
		tableName = tm[1]
	}

	id := ctx.FilePath + ":" + className
	n := model.NewCodeNode(id, model.NodeEntity, className)
	n.FQN = className
	n.FilePath = ctx.FilePath
	n.LineStart = base.FindLineNumber(text, cm[0])
	n.Source = "JpaEntityDetector"
	n.Confidence = model.ConfidenceLexical
	n.Properties["framework"] = "jpa"
	n.Properties["table_name"] = tableName

	var columns []string
	for _, m := range jpaColumnRE.FindAllStringSubmatch(text, -1) {
		if cn := jpaColNameRE.FindStringSubmatch(m[1]); len(cn) >= 2 {
			columns = append(columns, cn[1])
		}
	}
	if len(columns) > 0 {
		n.Properties["columns"] = columns
	}
	return detector.ResultOf([]*model.CodeNode{n}, nil)
}
