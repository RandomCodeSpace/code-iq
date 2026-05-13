package typescript

import (
	"fmt"
	"regexp"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// MongooseORMDetector ports
// io.github.randomcodespace.iq.detector.typescript.MongooseORMDetector.
type MongooseORMDetector struct{}

func NewMongooseORMDetector() *MongooseORMDetector { return &MongooseORMDetector{} }

func (MongooseORMDetector) Name() string                 { return "mongoose_orm" }
func (MongooseORMDetector) SupportedLanguages() []string { return []string{"typescript", "javascript"} }
func (MongooseORMDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewMongooseORMDetector()) }

var (
	mongooseModelRE   = regexp.MustCompile(`mongoose\.model\s*\(\s*['"](\w+)['"]`)
	mongooseSchemaRE  = regexp.MustCompile(`(?:const|let|var)\s+(\w+)\s*=\s*new\s+(?:mongoose\.)?Schema\s*\(`)
	mongooseConnectRE = regexp.MustCompile(`mongoose\.connect\s*\(`)
	mongooseQueryRE   = regexp.MustCompile(`(\w+)\.(find|findOne|findById|findOneAndUpdate|findOneAndDelete|create|insertMany|updateOne|updateMany|deleteOne|deleteMany|countDocuments|aggregate)\s*\(`)
	mongooseVirtualRE = regexp.MustCompile(`(\w+)\.virtual\s*\(\s*['"](\w+)['"]`)
	mongooseHookRE    = regexp.MustCompile(`(\w+)\.(pre|post)\s*\(\s*['"](\w+)['"]`)
)

func (d MongooseORMDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName
	seenModels := make(map[string]string)
	schemaVars := make(map[string]bool)

	// mongoose.connect -> DATABASE_CONNECTION
	for _, m := range mongooseConnectRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		id := fmt.Sprintf("mongoose:%s:connection:%d", filePath, line)
		n := model.NewCodeNode(id, model.NodeDatabaseConnection, "mongoose.connect")
		n.FQN = filePath + "::mongoose.connect"
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "MongooseORMDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["framework"] = "mongoose"
		nodes = append(nodes, n)
	}

	// new Schema({ ... }) -> ENTITY
	for _, m := range mongooseSchemaRE.FindAllStringSubmatchIndex(text, -1) {
		varName := text[m[2]:m[3]]
		schemaVars[varName] = true
		line := base.FindLineNumber(text, m[0])
		n := model.NewCodeNode(
			"mongoose:"+filePath+":schema:"+varName,
			model.NodeEntity, varName,
		)
		n.FQN = filePath + "::" + varName
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "MongooseORMDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["framework"] = "mongoose"
		n.Properties["definition"] = "schema"
		nodes = append(nodes, n)
	}

	// mongoose.model('Name', schema)
	for _, m := range mongooseModelRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		id := "mongoose:" + filePath + ":model:" + name
		seenModels[name] = id
		n := model.NewCodeNode(id, model.NodeEntity, name)
		n.FQN = filePath + "::" + name
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "MongooseORMDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["framework"] = "mongoose"
		n.Properties["definition"] = "model"
		nodes = append(nodes, n)
	}

	// Virtuals — collect by schema name (matching Java)
	var virtuals []string
	for _, m := range mongooseVirtualRE.FindAllStringSubmatch(text, -1) {
		varName := m[1]
		vname := m[2]
		if schemaVars[varName] {
			virtuals = append(virtuals, vname)
		}
	}
	if len(virtuals) > 0 {
		for _, n := range nodes {
			if n.Properties["definition"] == "schema" {
				n.Properties["virtuals"] = virtuals
			}
		}
	}

	// Hooks
	for _, m := range mongooseHookRE.FindAllStringSubmatchIndex(text, -1) {
		varName := text[m[2]:m[3]]
		hookType := text[m[4]:m[5]]
		eventName := text[m[6]:m[7]]
		if !schemaVars[varName] {
			continue
		}
		line := base.FindLineNumber(text, m[0])
		id := fmt.Sprintf("mongoose:%s:hook:%s:%s:%d", filePath, hookType, eventName, line)
		n := model.NewCodeNode(id, model.NodeEvent, hookType+":"+eventName)
		n.FQN = filePath + "::" + hookType + ":" + eventName
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "MongooseORMDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["framework"] = "mongoose"
		n.Properties["hook_type"] = hookType
		n.Properties["event"] = eventName
		nodes = append(nodes, n)
	}

	// Query operations
	for _, m := range mongooseQueryRE.FindAllStringSubmatchIndex(text, -1) {
		modelName := text[m[2]:m[3]]
		op := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])
		tgt, ok := seenModels[modelName]
		if !ok {
			tgt = "mongoose:" + filePath + ":model:" + modelName
		}
		e := model.NewCodeEdge(
			fmt.Sprintf("%s->queries->%s:%d", filePath, tgt, line),
			model.EdgeQueries, filePath, tgt,
		)
		e.Source = "MongooseORMDetector"
		e.Confidence = model.ConfidenceLexical
		e.Properties["operation"] = op
		e.Properties["line"] = line
		edges = append(edges, e)
	}

	return detector.ResultOf(nodes, edges)
}
