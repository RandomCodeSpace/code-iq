package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// CosmosDbDetector mirrors Java CosmosDbDetector. Detects Azure Cosmos DB
// database / container references in Java + TS/JS.
type CosmosDbDetector struct{}

func NewCosmosDbDetector() *CosmosDbDetector { return &CosmosDbDetector{} }

func (CosmosDbDetector) Name() string { return "cosmos_db" }
func (CosmosDbDetector) SupportedLanguages() []string {
	return []string{"java", "typescript", "javascript"}
}
func (CosmosDbDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewCosmosDbDetector()) }

var (
	cosmosClassRE     = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
	cosmosDatabaseRE  = regexp.MustCompile(`\.(?:getDatabase|database)\s*\(\s*"([^"]+)"`)
	cosmosContainerRE = regexp.MustCompile(`\.(?:getContainer|container)\s*\(\s*"([^"]+)"`)
)

func (CosmosDbDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "CosmosClient") && !strings.Contains(text, "CosmosDatabase") &&
		!strings.Contains(text, "CosmosContainer") && !strings.Contains(text, "@azure/cosmos") {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	className := ""
	for _, line := range lines {
		if m := cosmosClassRE.FindStringSubmatch(line); m != nil {
			className = m[1]
			break
		}
	}
	sourceID := ctx.FilePath
	if className != "" {
		sourceID = ctx.FilePath + ":" + className
	}

	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	seenDb := map[string]bool{}
	seenCont := map[string]bool{}

	for i, line := range lines {
		for _, m := range cosmosDatabaseRE.FindAllStringSubmatch(line, -1) {
			name := m[1]
			if seenDb[name] {
				continue
			}
			seenDb[name] = true
			id := "azure:cosmos:db:" + name
			n := model.NewCodeNode(id, model.NodeAzureResource, "cosmosdb:"+name)
			n.FilePath = ctx.FilePath
			n.LineStart = i + 1
			n.Source = "CosmosDbDetector"
			n.Confidence = base.RegexDetectorDefaultConfidence
			n.Properties["cosmos_type"] = "database"
			n.Properties["resource_name"] = name
			nodes = append(nodes, n)
			edges = append(edges, model.NewCodeEdge(sourceID+"->connects_to->"+id, model.EdgeConnectsTo, sourceID, id))
		}
		for _, m := range cosmosContainerRE.FindAllStringSubmatch(line, -1) {
			name := m[1]
			if seenCont[name] {
				continue
			}
			seenCont[name] = true
			id := "azure:cosmos:container:" + name
			n := model.NewCodeNode(id, model.NodeAzureResource, "cosmosdb-container:"+name)
			n.FilePath = ctx.FilePath
			n.LineStart = i + 1
			n.Source = "CosmosDbDetector"
			n.Confidence = base.RegexDetectorDefaultConfidence
			n.Properties["cosmos_type"] = "container"
			n.Properties["resource_name"] = name
			nodes = append(nodes, n)
			edges = append(edges, model.NewCodeEdge(sourceID+"->connects_to->"+id, model.EdgeConnectsTo, sourceID, id))
		}
	}
	return detector.ResultOf(nodes, edges)
}
