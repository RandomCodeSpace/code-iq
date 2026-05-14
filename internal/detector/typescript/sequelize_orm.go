package typescript

import (
	"fmt"
	"regexp"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// SequelizeORMDetector ports
// io.github.randomcodespace.iq.detector.typescript.SequelizeORMDetector.
type SequelizeORMDetector struct{}

func NewSequelizeORMDetector() *SequelizeORMDetector { return &SequelizeORMDetector{} }

func (SequelizeORMDetector) Name() string                 { return "sequelize_orm" }
func (SequelizeORMDetector) SupportedLanguages() []string { return []string{"typescript", "javascript"} }
func (SequelizeORMDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewSequelizeORMDetector()) }

var (
	sequelizeDefineRE       = regexp.MustCompile(`sequelize\.define\s*\(\s*['"](\w+)['"]`)
	sequelizeExtendsModelRE = regexp.MustCompile(`class\s+(\w+)\s+extends\s+Model\s*\{`)
	sequelizeConnectionRE   = regexp.MustCompile(`new\s+Sequelize(?:\.Sequelize)?\s*\(`)
	sequelizeAssocRE        = regexp.MustCompile(`(\w+)\.(belongsTo|hasMany|hasOne|belongsToMany)\s*\(\s*(\w+)`)
	sequelizeQueryRE        = regexp.MustCompile(`(\w+)\.(findAll|findOne|findByPk|findOrCreate|create|bulkCreate|update|destroy|count|max|min|sum)\s*\(`)
)

func (d SequelizeORMDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName
	seenModels := make(map[string]string)

	// new Sequelize(...) -> DATABASE_CONNECTION
	for _, m := range sequelizeConnectionRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		id := fmt.Sprintf("sequelize:%s:connection:%d", filePath, line)
		n := model.NewCodeNode(id, model.NodeDatabaseConnection, "Sequelize")
		n.FQN = filePath + "::Sequelize"
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "SequelizeORMDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["framework"] = "sequelize"
		nodes = append(nodes, n)
	}

	// sequelize.define('Name', { ... })
	for _, m := range sequelizeDefineRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		id := "sequelize:" + filePath + ":model:" + name
		seenModels[name] = id
		n := model.NewCodeNode(id, model.NodeEntity, name)
		n.FQN = filePath + "::" + name
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "SequelizeORMDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["framework"] = "sequelize"
		n.Properties["definition"] = "define"
		nodes = append(nodes, n)
	}

	// class X extends Model
	for _, m := range sequelizeExtendsModelRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		if _, ok := seenModels[name]; ok {
			continue
		}
		id := "sequelize:" + filePath + ":model:" + name
		seenModels[name] = id
		n := model.NewCodeNode(id, model.NodeEntity, name)
		n.FQN = filePath + "::" + name
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "SequelizeORMDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["framework"] = "sequelize"
		n.Properties["definition"] = "class"
		nodes = append(nodes, n)
	}

	// Associations
	for _, m := range sequelizeAssocRE.FindAllStringSubmatchIndex(text, -1) {
		src := text[m[2]:m[3]]
		assoc := text[m[4]:m[5]]
		tgt := text[m[6]:m[7]]
		line := base.FindLineNumber(text, m[0])
		srcID, ok := seenModels[src]
		if !ok {
			srcID = "sequelize:" + filePath + ":model:" + src
		}
		tgtID, ok := seenModels[tgt]
		if !ok {
			tgtID = "sequelize:" + filePath + ":model:" + tgt
		}
		e := model.NewCodeEdge(
			fmt.Sprintf("%s->%s->%s:%d", srcID, assoc, tgtID, line),
			model.EdgeDependsOn, srcID, tgtID,
		)
		e.Source = "SequelizeORMDetector"
		e.Confidence = model.ConfidenceLexical
		e.Properties["association"] = assoc
		e.Properties["line"] = line
		edges = append(edges, e)
	}

	// Query operations
	for _, m := range sequelizeQueryRE.FindAllStringSubmatchIndex(text, -1) {
		modelName := text[m[2]:m[3]]
		op := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])
		tgt, ok := seenModels[modelName]
		if !ok {
			tgt = "sequelize:" + filePath + ":model:" + modelName
		}
		e := model.NewCodeEdge(
			fmt.Sprintf("%s->queries->%s:%d", filePath, tgt, line),
			model.EdgeQueries, filePath, tgt,
		)
		e.Source = "SequelizeORMDetector"
		e.Confidence = model.ConfidenceLexical
		e.Properties["operation"] = op
		e.Properties["line"] = line
		edges = append(edges, e)
	}

	return detector.ResultOf(nodes, edges)
}
