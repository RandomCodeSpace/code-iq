// Package csharp holds C#/.NET detectors.
package csharp

import (
	"regexp"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// EfcoreDetector detects Entity Framework Core DbContexts, DbSet entities,
// and migration classes / CreateTable calls. Mirrors Java CSharpEfcoreDetector.
type EfcoreDetector struct{}

func NewEfcoreDetector() *EfcoreDetector { return &EfcoreDetector{} }

func (EfcoreDetector) Name() string                        { return "csharp_efcore" }
func (EfcoreDetector) SupportedLanguages() []string        { return []string{"csharp"} }
func (EfcoreDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewEfcoreDetector()) }

var (
	efcoreDbContextRE   = regexp.MustCompile(`(?m)class\s+(\w+)\s*:\s*(?:[\w.]+\.)?DbContext`)
	efcoreDbSetRE       = regexp.MustCompile(`(?m)DbSet<(\w+)>`)
	efcoreMigrationRE   = regexp.MustCompile(`(?m)class\s+(\w+)\s*:\s*Migration`)
	efcoreCreateTableRE = regexp.MustCompile(`(?m)CreateTable\s*\(\s*(?:name:\s*)?"(\w+)"`)
)

const propEfcore = "efcore"

func (d EfcoreDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	filePath := ctx.FilePath
	var contextIDs []string

	// DbContexts → REPOSITORY
	for _, m := range efcoreDbContextRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		nodeID := "efcore:" + filePath + ":context:" + name
		contextIDs = append(contextIDs, nodeID)
		n := model.NewCodeNode(nodeID, model.NodeRepository, name)
		n.FQN = name
		n.FilePath = filePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "CSharpEfcoreDetector"
		n.Properties["framework"] = propEfcore
		nodes = append(nodes, n)
	}

	// DbSet<T> entities — track seen IDs to avoid duplicates from CreateTable
	seen := map[string]bool{}
	for _, m := range efcoreDbSetRE.FindAllStringSubmatchIndex(text, -1) {
		entity := text[m[2]:m[3]]
		entityID := "efcore:" + filePath + ":entity:" + entity
		if !seen[entityID] {
			seen[entityID] = true
			n := model.NewCodeNode(entityID, model.NodeEntity, entity)
			n.FQN = entity
			n.FilePath = filePath
			n.LineStart = base.FindLineNumber(text, m[0])
			n.Source = "CSharpEfcoreDetector"
			n.Properties["framework"] = propEfcore
			nodes = append(nodes, n)
		}
		// QUERIES edge for each context
		for _, ctxID := range contextIDs {
			e := model.NewCodeEdge(
				ctxID+":queries:"+entity,
				model.EdgeQueries, ctxID, entityID,
			)
			e.Source = "CSharpEfcoreDetector"
			edges = append(edges, e)
		}
	}

	// Migration classes
	for _, m := range efcoreMigrationRE.FindAllStringSubmatchIndex(text, -1) {
		name := text[m[2]:m[3]]
		n := model.NewCodeNode(
			"efcore:"+filePath+":migration:"+name,
			model.NodeMigration, name,
		)
		n.FQN = name
		n.FilePath = filePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "CSharpEfcoreDetector"
		n.Properties["framework"] = propEfcore
		nodes = append(nodes, n)
	}

	// CreateTable entries — emit entities for tables not already seen
	for _, m := range efcoreCreateTableRE.FindAllStringSubmatchIndex(text, -1) {
		table := text[m[2]:m[3]]
		entityID := "efcore:" + filePath + ":entity:" + table
		if seen[entityID] {
			continue
		}
		seen[entityID] = true
		n := model.NewCodeNode(entityID, model.NodeEntity, table)
		n.FQN = table
		n.FilePath = filePath
		n.LineStart = base.FindLineNumber(text, m[0])
		n.Source = "CSharpEfcoreDetector"
		n.Properties["framework"] = propEfcore
		n.Properties["source"] = "migration"
		nodes = append(nodes, n)
	}

	return detector.ResultOf(nodes, edges)
}
