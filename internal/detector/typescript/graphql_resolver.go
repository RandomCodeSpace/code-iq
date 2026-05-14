package typescript

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// GraphQLResolverDetector ports
// io.github.randomcodespace.iq.detector.typescript.GraphQLResolverDetector.
type GraphQLResolverDetector struct{}

func NewGraphQLResolverDetector() *GraphQLResolverDetector { return &GraphQLResolverDetector{} }

func (GraphQLResolverDetector) Name() string                 { return "typescript.graphql_resolvers" }
func (GraphQLResolverDetector) SupportedLanguages() []string { return []string{"typescript", "javascript"} }
func (GraphQLResolverDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewGraphQLResolverDetector()) }

var (
	gqlResolverRE = regexp.MustCompile(
		`@Resolver\(\s*(?:of\s*=>\s*)?(\w+)?\s*\)\s*\n\s*(?:export\s+)?class\s+(\w+)`)
	gqlQueryRE = regexp.MustCompile(
		`(?s)@(Query|Mutation|Subscription)\(.*?\)\s*\n\s*(?:async\s+)?(\w+)`)
	gqlTypedefRE = regexp.MustCompile(
		`type\s+(Query|Mutation|Subscription)\s*\{([^}]+)\}`)
	gqlResolverFieldRE = regexp.MustCompile(`(\w+)\s*(?:\([^)]*\))?\s*:`)
)

func (d GraphQLResolverDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	var nodes []*model.CodeNode
	filePath := ctx.FilePath
	moduleName := ctx.ModuleName

	// NestJS-style resolvers
	for _, m := range gqlResolverRE.FindAllStringSubmatchIndex(text, -1) {
		entityType := ""
		if m[2] >= 0 {
			entityType = text[m[2]:m[3]]
		}
		className := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])
		classID := "class:" + filePath + "::" + className
		n := model.NewCodeNode(classID, model.NodeClass, className)
		n.FQN = filePath + "::" + className
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "GraphQLResolverDetector"
		n.Confidence = model.ConfidenceLexical
		n.Annotations = append(n.Annotations, "@Resolver")
		n.Properties["framework"] = "nestjs-graphql"
		if entityType != "" {
			n.Properties["entity_type"] = entityType
		}
		nodes = append(nodes, n)
	}

	// @Query / @Mutation / @Subscription
	for _, m := range gqlQueryRE.FindAllStringSubmatchIndex(text, -1) {
		opType := text[m[2]:m[3]]
		funcName := text[m[4]:m[5]]
		line := base.FindLineNumber(text, m[0])
		nodeID := "endpoint:" + moduleName + ":graphql:" + opType + ":" + funcName
		n := model.NewCodeNode(nodeID, model.NodeEndpoint, "GraphQL "+opType+": "+funcName)
		n.FQN = filePath + "::" + funcName
		n.Module = moduleName
		n.FilePath = filePath
		n.LineStart = line
		n.Source = "GraphQLResolverDetector"
		n.Confidence = model.ConfidenceLexical
		n.Properties["protocol"] = "GraphQL"
		n.Properties["operation_type"] = strings.ToLower(opType)
		n.Properties["field_name"] = funcName
		nodes = append(nodes, n)
	}

	// Schema-defined resolvers
	for _, m := range gqlTypedefRE.FindAllStringSubmatchIndex(text, -1) {
		opType := text[m[2]:m[3]]
		fieldsBlock := text[m[4]:m[5]]
		baseLine := base.FindLineNumber(text, m[0])

		for _, fm := range gqlResolverFieldRE.FindAllStringSubmatch(fieldsBlock, -1) {
			fieldName := fm[1]
			nodeID := "endpoint:" + moduleName + ":graphql:" + opType + ":" + fieldName
			n := model.NewCodeNode(nodeID, model.NodeEndpoint, "GraphQL "+opType+": "+fieldName)
			n.Module = moduleName
			n.FilePath = filePath
			n.LineStart = baseLine
			n.Source = "GraphQLResolverDetector"
			n.Confidence = model.ConfidenceLexical
			n.Properties["protocol"] = "GraphQL"
			n.Properties["operation_type"] = strings.ToLower(opType)
			n.Properties["field_name"] = fieldName
			nodes = append(nodes, n)
		}
	}

	return detector.ResultOf(nodes, nil)
}
