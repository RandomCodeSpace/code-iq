package typescript

import (
	"fmt"
	"regexp"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// PrismaORMDetector ports
// io.github.randomcodespace.iq.detector.typescript.PrismaORMDetector.
type PrismaORMDetector struct{}

func NewPrismaORMDetector() *PrismaORMDetector { return &PrismaORMDetector{} }

func (PrismaORMDetector) Name() string                 { return "prisma_orm" }
func (PrismaORMDetector) SupportedLanguages() []string { return []string{"typescript", "javascript"} }
func (PrismaORMDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewPrismaORMDetector()) }

var (
	prismaOpRE          = regexp.MustCompile(`prisma\.(\w+)\.(findMany|findFirst|findUnique|create|update|delete|upsert|count|aggregate|groupBy)\s*\(`)
	prismaClientRE      = regexp.MustCompile(`new\s+PrismaClient\s*\(|PrismaClient\s*\(`)
	prismaImportRE      = regexp.MustCompile(`(?:import|require)\s*\(?[^)]*['"]@prisma/client['"]`)
	prismaTransactionRE = regexp.MustCompile(`prisma\.\$transaction\s*\(`)
)

func (d PrismaORMDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName
	hasTx := prismaTransactionRE.MatchString(text)

	// PrismaClient instantiation -> DATABASE_CONNECTION
	for _, m := range prismaClientRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		id := fmt.Sprintf("prisma:%s:client:%d", filePath, line)
		n := model.NewCodeNode(id, model.NodeDatabaseConnection, "PrismaClient")
		n.FQN = filePath + "::PrismaClient"
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "PrismaORMDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["framework"] = "prisma"
		if hasTx {
			n.Properties["transaction"] = true
		}
		nodes = append(nodes, n)
	}

	// @prisma/client imports -> IMPORTS edge
	for _, m := range prismaImportRE.FindAllStringIndex(text, -1) {
		line := base.FindLineNumber(text, m[0])
		e := model.NewCodeEdge(
			fmt.Sprintf("%s->imports->@prisma/client:%d", filePath, line),
			model.EdgeImports, filePath, "@prisma/client",
		)
		e.Source = "PrismaORMDetector"
		e.Confidence = model.ConfidenceLexical
		e.Properties["line"] = line
		edges = append(edges, e)
	}

	// prisma model operations -> ENTITY nodes + QUERIES edges
	seen := make(map[string]string)
	for _, m := range prismaOpRE.FindAllStringSubmatchIndex(text, -1) {
		modelName := text[m[2]:m[3]]
		op := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])

		if _, ok := seen[modelName]; !ok {
			id := "prisma:" + filePath + ":model:" + modelName
			seen[modelName] = id
			n := model.NewCodeNode(id, model.NodeEntity, modelName)
			n.FQN = filePath + "::" + modelName
			n.Module = moduleName
			n.FilePath = filePath
			n.LineStart = line
			n.Source = "PrismaORMDetector"
			n.Confidence = model.ConfidenceLexical
			n.Properties["framework"] = "prisma"
			nodes = append(nodes, n)
		}
		e := model.NewCodeEdge(
			fmt.Sprintf("%s->queries->%s:%d", filePath, seen[modelName], line),
			model.EdgeQueries, filePath, seen[modelName],
		)
		e.Source = "PrismaORMDetector"
		e.Confidence = model.ConfidenceLexical
		e.Properties["operation"] = op
		e.Properties["line"] = line
		edges = append(edges, e)
	}

	return detector.ResultOf(nodes, edges)
}
