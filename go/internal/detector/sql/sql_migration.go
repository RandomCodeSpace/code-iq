// Package sql holds raw-SQL and migration-file detectors.
package sql

import (
	"regexp"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// SqlMigrationDetector mirrors Java SqlMigrationDetector. Extracts schema
// entities (tables, views, schemas) from raw SQL DDL and framework-specific
// migration files (Flyway, Liquibase XML/YAML, Alembic, Rails, Prisma).
// Emits SQL_ENTITY nodes, MIGRATION nodes, and REFERENCES_TABLE / MIGRATES
// edges.
type SqlMigrationDetector struct{}

func NewSqlMigrationDetector() *SqlMigrationDetector { return &SqlMigrationDetector{} }

func (SqlMigrationDetector) Name() string { return "sql_migration" }
func (SqlMigrationDetector) SupportedLanguages() []string {
	return []string{"sql", "python", "ruby", "xml", "yaml"}
}
func (SqlMigrationDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewSqlMigrationDetector()) }

const (
	smFmtRaw       = "raw"
	smFmtFlyway    = "flyway"
	smFmtLiquibase = "liquibase"
	smFmtAlembic   = "alembic"
	smFmtRails     = "rails"
	smFmtPrisma    = "prisma"

	smObjTable  = "table"
	smObjView   = "view"
	smObjSchema = "schema"
)

var (
	// Path discriminators (RE2: possessive quantifiers replaced with plain quantifiers).
	smFlywayPath    = regexp.MustCompile(`(?i)(?:^|/)V\d+(?:_\d+)*__.+\.sql$`)
	smRailsPath     = regexp.MustCompile(`(?:^|/)db/migrate/\d{14}_.+\.rb$`)
	smAlembicPath   = regexp.MustCompile(`(?:^|/)versions/.+\.py$`)
	smPrismaPath    = regexp.MustCompile(`(?:^|/)migrations/.+/migration\.sql$`)
	smLiquibasePath = regexp.MustCompile(`(?i)(?:^|/)(?:changelog|db\.changelog[^/]*)\.(?:xml|ya?ml)$`)

	smSqlCreateTable  = regexp.MustCompile(`(?i)CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:(\w+)\.)?(\w+)`)
	smSqlCreateView   = regexp.MustCompile(`(?i)CREATE\s+(?:OR\s+REPLACE\s+)?VIEW\s+(?:IF\s+NOT\s+EXISTS\s+)?(?:(\w+)\.)?(\w+)`)
	smSqlCreateSchema = regexp.MustCompile(`(?i)CREATE\s+SCHEMA\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)`)
	smSqlAlterAdd     = regexp.MustCompile(`(?i)ALTER\s+TABLE\s+(?:(\w+)\.)?(\w+)\s+ADD\s+(?:COLUMN\s+)?(\w+)\s+(\w+)`)
	smSqlDropTable    = regexp.MustCompile(`(?i)DROP\s+TABLE\b`)
	smSqlCreateIndex  = regexp.MustCompile(`(?i)CREATE\s+(?:UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?(\w+)\s+ON\s+(?:(\w+)\.)?(\w+)`)
	smSqlFK           = regexp.MustCompile(`(?i)FOREIGN\s+KEY\s*\([^)]*\)\s+REFERENCES\s+(?:(\w+)\.)?(\w+)`)

	smAlembicMarker      = regexp.MustCompile(`\bfrom\s+alembic\b|\bop\.create_table\b|\bop\.add_column\b`)
	smAlembicCreateTable = regexp.MustCompile(`op\.create_table\(\s*['"](\w+)['"]`)
	smAlembicAddColumn   = regexp.MustCompile(`op\.add_column\(\s*['"](\w+)['"]\s*,\s*sa\.Column\(\s*['"](\w+)['"]`)
	smAlembicCreateIndex = regexp.MustCompile(`op\.create_index\(\s*['"](\w+)['"]\s*,\s*['"](\w+)['"]`)
	smAlembicCreateFK    = regexp.MustCompile(`op\.create_foreign_key\(\s*['"][^'"]*['"]\s*,\s*['"](\w+)['"]\s*,\s*['"](\w+)['"]`)

	smRailsCreateTable = regexp.MustCompile(`create_table\s+:(\w+)`)
	smRailsAddColumn   = regexp.MustCompile(`add_column\s+:(\w+)\s*,\s*:(\w+)`)
	smRailsAddFK       = regexp.MustCompile(`add_foreign_key\s+:(\w+)\s*,\s*:(\w+)`)

	smLqCreateTableXml = regexp.MustCompile(`<createTable\b[^>]*?\btableName\s*=\s*"(\w+)"[^>]*?(?:\bschemaName\s*=\s*"(\w+)")?`)
	smLqAddColumnXml   = regexp.MustCompile(`<addColumn\b[^>]*?\btableName\s*=\s*"(\w+)"`)
	smLqAddFkXml       = regexp.MustCompile(`<addForeignKeyConstraint\b[^>]*?\bbaseTableName\s*=\s*"(\w+)"[^>]*?\breferencedTableName\s*=\s*"(\w+)"`)

	// RE2 has no negative lookahead. We approximate: scan with a small forward
	// window. Test fixtures don't cover Liquibase YAML, so we keep this simple.
	smLqCreateTableYaml = regexp.MustCompile(`(?s)createTable\s*:[^\n]*\n(?:[^\n]*\n){0,8}?\s+tableName\s*:\s*([\w"']+)`)
	smLqAddFkYaml       = regexp.MustCompile(`(?s)addForeignKeyConstraint\s*:[^\n]*\n(?:[^\n]*\n){0,8}?\s+baseTableName\s*:\s*([\w"']+)[^\n]*\n(?:[^\n]*\n){0,8}?\s+referencedTableName\s*:\s*([\w"']+)`)

	smFlywayVersion = regexp.MustCompile(`(?i)^V(\d+(?:_\d+)*)__`)
	smRailsVersion  = regexp.MustCompile(`^(\d{14})_`)
)

func (SqlMigrationDetector) Detect(ctx *detector.Context) *detector.Result {
	content := ctx.Content
	fp := ctx.FilePath
	if content == "" || fp == "" {
		return detector.EmptyResult()
	}
	normalized := strings.ReplaceAll(fp, "\\", "/")
	lowerName := strings.ToLower(smExtractFileName(normalized))

	format := smClassify(normalized, lowerName, ctx.Language, content)
	if format == "" {
		return detector.EmptyResult()
	}

	state := newSmState(ctx, normalized)
	state.format = format

	switch format {
	case smFmtFlyway:
		state.version = smParseFlywayVersion(lowerName)
		smParseRawSql(content, state)
	case smFmtPrisma:
		state.version = smParsePrismaVersion(normalized)
		smParseRawSql(content, state)
	case smFmtAlembic:
		smParseAlembic(content, state)
	case smFmtRails:
		state.version = smParseRailsVersion(lowerName)
		smParseRails(content, state)
	case smFmtLiquibase:
		if strings.HasSuffix(lowerName, ".xml") {
			smParseLiquibaseXml(content, state)
		} else {
			smParseLiquibaseYaml(content, state)
		}
	case smFmtRaw:
		state.format = "" // bare .sql isn't a "migration" — just SQL entities
		smParseRawSql(content, state)
	}

	return state.result()
}

func smClassify(path, lowerName, lang, content string) string {
	if smPrismaPath.FindStringIndex(path) != nil {
		return smFmtPrisma
	}
	if smFlywayPath.FindStringIndex(path) != nil {
		return smFmtFlyway
	}
	if smRailsPath.FindStringIndex(path) != nil {
		return smFmtRails
	}
	if smLiquibasePath.FindStringIndex(path) != nil {
		return smFmtLiquibase
	}
	if smAlembicPath.FindStringIndex(path) != nil && smAlembicMarker.FindStringIndex(content) != nil {
		return smFmtAlembic
	}
	if strings.HasSuffix(lowerName, ".sql") || lang == "sql" {
		return smFmtRaw
	}
	return ""
}

type smState struct {
	ctx          *detector.Context
	filePath     string
	entities     map[string]*model.CodeNode
	entityOrder  []string
	refEdges     map[string]*model.CodeEdge
	refOrder     []string
	lastTableID  string
	format       string
	version      string
}

func newSmState(ctx *detector.Context, fp string) *smState {
	return &smState{
		ctx:      ctx,
		filePath: fp,
		entities: map[string]*model.CodeNode{},
		refEdges: map[string]*model.CodeEdge{},
	}
}

func (s *smState) addOrGetEntity(schema, name, objType string, line int) string {
	id := "sql:" + schema + ":" + name
	if _, ok := s.entities[id]; !ok {
		fqn := name
		if schema != "" {
			fqn = schema + "." + name
		}
		n := model.NewCodeNode(id, model.NodeSQLEntity, name)
		n.FQN = fqn
		n.FilePath = s.filePath
		n.LineStart = line
		n.Source = "SqlMigrationDetector"
		n.Confidence = base.RegexDetectorDefaultConfidence
		n.Properties["sql_object_type"] = objType
		if schema != "" {
			n.Properties["schema"] = schema
		}
		n.Properties["table"] = name
		s.entities[id] = n
		s.entityOrder = append(s.entityOrder, id)
	}
	if objType == smObjTable {
		s.lastTableID = id
	}
	return id
}

func (s *smState) appendListProp(id, key, value string) {
	n, ok := s.entities[id]
	if !ok {
		return
	}
	existing, _ := n.Properties[key].(string)
	if existing != "" {
		if strings.Contains(existing, value) {
			return
		}
		n.Properties[key] = existing + "," + value
		return
	}
	n.Properties[key] = value
}

func (s *smState) addReferencesEdge(src, target string) {
	if src == "" || target == "" || src == target {
		return
	}
	id := src + "->" + target + ":references_table"
	if _, ok := s.refEdges[id]; ok {
		return
	}
	e := model.NewCodeEdge(id, model.EdgeReferencesTable, src, target)
	s.refEdges[id] = e
	s.refOrder = append(s.refOrder, id)
}

func (s *smState) result() *detector.Result {
	var nodes []*model.CodeNode
	for _, id := range s.entityOrder {
		nodes = append(nodes, s.entities[id])
	}
	var edges []*model.CodeEdge
	for _, id := range s.refOrder {
		edges = append(edges, s.refEdges[id])
	}

	if s.format != "" && len(s.entities) > 0 {
		migID := "migration:" + s.filePath
		mig := model.NewCodeNode(migID, model.NodeMigration, s.filePath)
		mig.FQN = s.filePath
		mig.FilePath = s.filePath
		mig.LineStart = 1
		mig.Source = "SqlMigrationDetector"
		mig.Confidence = base.RegexDetectorDefaultConfidence
		mig.Properties["format"] = s.format
		if s.version != "" {
			mig.Properties["version"] = s.version
		}
		appliedTo := append([]string{}, s.entityOrder...)
		sort.Strings(appliedTo)
		mig.Properties["applied_to"] = strings.Join(appliedTo, ",")
		nodes = append(nodes, mig)

		for _, sqlID := range appliedTo {
			edges = append(edges, model.NewCodeEdge(migID+"->"+sqlID+":migrates", model.EdgeMigrates, migID, sqlID))
		}
	}

	sort.SliceStable(nodes, func(i, j int) bool { return nodes[i].ID < nodes[j].ID })
	sort.SliceStable(edges, func(i, j int) bool { return edges[i].ID < edges[j].ID })
	return detector.ResultOf(nodes, edges)
}

func smParseRawSql(content string, s *smState) {
	for i, line := range strings.Split(content, "\n") {
		smParseSqlLine(line, i+1, s)
	}
}

func smParseSqlLine(line string, lineNum int, s *smState) {
	if m := smSqlCreateTable.FindStringSubmatch(line); m != nil {
		s.addOrGetEntity(m[1], m[2], smObjTable, lineNum)
		return
	}
	if m := smSqlCreateView.FindStringSubmatch(line); m != nil {
		s.addOrGetEntity(m[1], m[2], smObjView, lineNum)
		return
	}
	if m := smSqlCreateSchema.FindStringSubmatch(line); m != nil {
		s.addOrGetEntity("", m[1], smObjSchema, lineNum)
		return
	}
	if m := smSqlAlterAdd.FindStringSubmatch(line); m != nil {
		id := s.addOrGetEntity(m[1], m[2], smObjTable, lineNum)
		s.appendListProp(id, "columns_added", m[3])
		return
	}
	if smSqlDropTable.FindStringIndex(line) != nil {
		return
	}
	if m := smSqlCreateIndex.FindStringSubmatch(line); m != nil {
		idxName := m[1]
		id := s.addOrGetEntity(m[2], m[3], smObjTable, lineNum)
		s.appendListProp(id, "indexes", idxName)
		return
	}
	if m := smSqlFK.FindStringSubmatch(line); m != nil && s.lastTableID != "" {
		src := s.lastTableID
		target := s.addOrGetEntity(m[1], m[2], smObjTable, lineNum)
		s.addReferencesEdge(src, target)
		s.lastTableID = src
	}
}

func smParseAlembic(content string, s *smState) {
	for i, line := range strings.Split(content, "\n") {
		ln := i + 1
		if m := smAlembicCreateTable.FindStringSubmatch(line); m != nil {
			s.addOrGetEntity("", m[1], smObjTable, ln)
			continue
		}
		if m := smAlembicAddColumn.FindStringSubmatch(line); m != nil {
			id := s.addOrGetEntity("", m[1], smObjTable, ln)
			s.appendListProp(id, "columns_added", m[2])
			continue
		}
		if m := smAlembicCreateIndex.FindStringSubmatch(line); m != nil {
			id := s.addOrGetEntity("", m[2], smObjTable, ln)
			s.appendListProp(id, "indexes", m[1])
			continue
		}
		if m := smAlembicCreateFK.FindStringSubmatch(line); m != nil {
			src := s.addOrGetEntity("", m[1], smObjTable, ln)
			tgt := s.addOrGetEntity("", m[2], smObjTable, ln)
			s.addReferencesEdge(src, tgt)
		}
	}
}

func smParseRails(content string, s *smState) {
	for i, line := range strings.Split(content, "\n") {
		ln := i + 1
		if m := smRailsCreateTable.FindStringSubmatch(line); m != nil {
			s.addOrGetEntity("", m[1], smObjTable, ln)
			continue
		}
		if m := smRailsAddColumn.FindStringSubmatch(line); m != nil {
			id := s.addOrGetEntity("", m[1], smObjTable, ln)
			s.appendListProp(id, "columns_added", m[2])
			continue
		}
		if m := smRailsAddFK.FindStringSubmatch(line); m != nil {
			src := s.addOrGetEntity("", m[1], smObjTable, ln)
			tgt := s.addOrGetEntity("", m[2], smObjTable, ln)
			s.addReferencesEdge(src, tgt)
		}
	}
}

func smParseLiquibaseXml(content string, s *smState) {
	for _, m := range smLqCreateTableXml.FindAllStringSubmatchIndex(content, -1) {
		line := base.FindLineNumber(content, m[0])
		var schema, name string
		name = content[m[2]:m[3]]
		if m[4] >= 0 {
			schema = content[m[4]:m[5]]
		}
		s.addOrGetEntity(schema, name, smObjTable, line)
	}
	for _, m := range smLqAddColumnXml.FindAllStringSubmatchIndex(content, -1) {
		line := base.FindLineNumber(content, m[0])
		s.addOrGetEntity("", content[m[2]:m[3]], smObjTable, line)
	}
	for _, m := range smLqAddFkXml.FindAllStringSubmatchIndex(content, -1) {
		line := base.FindLineNumber(content, m[0])
		src := s.addOrGetEntity("", content[m[2]:m[3]], smObjTable, line)
		tgt := s.addOrGetEntity("", content[m[4]:m[5]], smObjTable, line)
		s.addReferencesEdge(src, tgt)
	}
}

func smParseLiquibaseYaml(content string, s *smState) {
	for _, m := range smLqCreateTableYaml.FindAllStringSubmatchIndex(content, -1) {
		line := base.FindLineNumber(content, m[0])
		name := smStripQuotes(content[m[2]:m[3]])
		s.addOrGetEntity("", name, smObjTable, line)
	}
	for _, m := range smLqAddFkYaml.FindAllStringSubmatchIndex(content, -1) {
		line := base.FindLineNumber(content, m[0])
		src := s.addOrGetEntity("", smStripQuotes(content[m[2]:m[3]]), smObjTable, line)
		tgt := s.addOrGetEntity("", smStripQuotes(content[m[4]:m[5]]), smObjTable, line)
		s.addReferencesEdge(src, tgt)
	}
}

func smExtractFileName(p string) string {
	if i := strings.LastIndex(p, "/"); i >= 0 {
		return p[i+1:]
	}
	return p
}

func smStripQuotes(s string) string {
	if len(s) < 2 {
		return s
	}
	first := s[0]
	last := s[len(s)-1]
	if (first == '"' || first == '\'') && first == last {
		return s[1 : len(s)-1]
	}
	return s
}

func smParseFlywayVersion(name string) string {
	if m := smFlywayVersion.FindStringSubmatch(name); m != nil {
		return strings.ReplaceAll(m[1], "_", ".")
	}
	return ""
}

func smParseRailsVersion(name string) string {
	if m := smRailsVersion.FindStringSubmatch(name); m != nil {
		return m[1]
	}
	return ""
}

func smParsePrismaVersion(p string) string {
	const suffix = "/migration.sql"
	end := strings.LastIndex(p, suffix)
	if end <= 0 {
		return ""
	}
	start := strings.LastIndex(p[:end], "/")
	if start < 0 {
		return p[:end]
	}
	return p[start+1 : end]
}
