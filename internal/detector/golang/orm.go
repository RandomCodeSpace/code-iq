package golang

import (
	"regexp"
	"strconv"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// OrmDetector detects Go ORM usage: GORM models/queries/migrations, sqlx
// connections/queries, and database/sql connections/queries.
// Mirrors Java GoOrmDetector (regex-only — Java side also defaults to regex).
type OrmDetector struct{}

func NewOrmDetector() *OrmDetector { return &OrmDetector{} }

func (OrmDetector) Name() string                        { return "go_orm" }
func (OrmDetector) SupportedLanguages() []string        { return []string{"go"} }
func (OrmDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewOrmDetector()) }

var (
	gormModelRE      = regexp.MustCompile(`(?s)type\s+(\w+)\s+struct\s*\{[^}]*gorm\.Model`)
	gormMigrateRE    = regexp.MustCompile(`(?m)\.AutoMigrate\s*\(`)
	gormQueryRE      = regexp.MustCompile(`(?m)\.(Create|Find|Where|First|Save|Delete)\s*\(`)
	sqlxConnectRE    = regexp.MustCompile(`(?m)sqlx\.(Connect|Open)\s*\(`)
	sqlxQueryRE      = regexp.MustCompile(`(?m)\.(Select|Get|NamedExec)\s*\(`)
	sqlOpenRE        = regexp.MustCompile(`(?m)sql\.Open\s*\(`)
	sqlQueryRE       = regexp.MustCompile(`(?m)\.(Query|QueryRow|Exec)\s*\(`)
	gormImportRE     = regexp.MustCompile(`"gorm\.io/`)
	sqlxImportRE     = regexp.MustCompile(`"github\.com/jmoiron/sqlx"`)
	databaseSqlImpRE = regexp.MustCompile(`"database/sql"`)
)

const (
	frameworkGorm        = "gorm"
	frameworkSqlx        = "sqlx"
	frameworkDatabaseSql = "database_sql"
)

func detectGoOrm(text string) string {
	if gormImportRE.MatchString(text) {
		return frameworkGorm
	}
	if sqlxImportRE.MatchString(text) {
		return frameworkSqlx
	}
	if databaseSqlImpRE.MatchString(text) {
		return frameworkDatabaseSql
	}
	return ""
}

func (d OrmDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	orm := detectGoOrm(text)

	// GORM entities
	for _, m := range gormModelRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode(
			"go_orm:"+filePath+":entity:"+name+":"+strconv.Itoa(line),
			model.NodeEntity, name,
		)
		n.FQN = filePath + "::" + name
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "GoOrmDetector"
		n.Properties["framework"] = frameworkGorm
		n.Properties["type"] = "model"
		nodes = append(nodes, n)
	}

	// GORM migrations
	for _, m := range gormMigrateRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode(
			"go_orm:"+filePath+":migration:"+strconv.Itoa(line),
			model.NodeMigration, "AutoMigrate",
		)
		n.FQN = filePath + "::AutoMigrate"
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "GoOrmDetector"
		n.Properties["framework"] = frameworkGorm
		n.Properties["type"] = "auto_migrate"
		nodes = append(nodes, n)
	}

	// GORM queries
	if orm == frameworkGorm {
		for _, m := range gormQueryRE.FindAllStringSubmatchIndex(text, -1) {
			op := text[m[2]:m[3]]
			line := base.FindLineNumber(text, m[0])
			targetID := "go_orm:" + filePath + ":query:" + op + ":" + strconv.Itoa(line)
			e := model.NewCodeEdge(
				filePath+":gorm:"+op+":"+strconv.Itoa(line),
				model.EdgeQueries, filePath, targetID,
			)
			e.Source = "GoOrmDetector"
			e.Properties["framework"] = frameworkGorm
			e.Properties["operation"] = op
			edges = append(edges, e)
		}
	}

	// sqlx connections
	for _, m := range sqlxConnectRE.FindAllStringSubmatchIndex(text, -1) {
		op := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode(
			"go_orm:"+filePath+":connection:sqlx:"+strconv.Itoa(line),
			model.NodeDatabaseConnection, "sqlx."+op,
		)
		n.FQN = filePath + "::sqlx." + op
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "GoOrmDetector"
		n.Properties["framework"] = frameworkSqlx
		n.Properties["operation"] = op
		nodes = append(nodes, n)
	}

	// sqlx queries
	if orm == frameworkSqlx {
		for _, m := range sqlxQueryRE.FindAllStringSubmatchIndex(text, -1) {
			op := text[m[2]:m[3]]
			line := base.FindLineNumber(text, m[0])
			targetID := "go_orm:" + filePath + ":query:sqlx:" + op + ":" + strconv.Itoa(line)
			e := model.NewCodeEdge(
				filePath+":sqlx:"+op+":"+strconv.Itoa(line),
				model.EdgeQueries, filePath, targetID,
			)
			e.Source = "GoOrmDetector"
			e.Properties["framework"] = frameworkSqlx
			e.Properties["operation"] = op
			edges = append(edges, e)
		}
	}

	// database/sql connections
	for _, m := range sqlOpenRE.FindAllStringSubmatchIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode(
			"go_orm:"+filePath+":connection:sql:"+strconv.Itoa(line),
			model.NodeDatabaseConnection, "sql.Open",
		)
		n.FQN = filePath + "::sql.Open"
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "GoOrmDetector"
		n.Properties["framework"] = frameworkDatabaseSql
		n.Properties["operation"] = "Open"
		nodes = append(nodes, n)
	}

	// database/sql queries
	if orm == frameworkDatabaseSql {
		for _, m := range sqlQueryRE.FindAllStringSubmatchIndex(text, -1) {
			op := text[m[2]:m[3]]
			line := base.FindLineNumber(text, m[0])
			targetID := "go_orm:" + filePath + ":query:sql:" + op + ":" + strconv.Itoa(line)
			e := model.NewCodeEdge(
				filePath+":sql:"+op+":"+strconv.Itoa(line),
				model.EdgeQueries, filePath, targetID,
			)
			e.Source = "GoOrmDetector"
			e.Properties["framework"] = frameworkDatabaseSql
			e.Properties["operation"] = op
			edges = append(edges, e)
		}
	}

	return detector.ResultOf(nodes, edges)
}
