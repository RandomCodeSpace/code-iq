package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// JavaGraphqlResolverDetector mirrors Java GraphqlResolverDetector. Detects
// Spring GraphQL (@QueryMapping/@MutationMapping/@SubscriptionMapping/
// @SchemaMapping) and Netflix DGS (@DgsQuery/@DgsMutation/@DgsSubscription/
// @DgsData) resolvers.
type JavaGraphqlResolverDetector struct{}

func NewJavaGraphqlResolverDetector() *JavaGraphqlResolverDetector {
	return &JavaGraphqlResolverDetector{}
}

func (JavaGraphqlResolverDetector) Name() string                 { return "graphql_resolver" }
func (JavaGraphqlResolverDetector) SupportedLanguages() []string { return []string{"java"} }
func (JavaGraphqlResolverDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewJavaGraphqlResolverDetector()) }

var (
	jgqlClassRE       = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
	jgqlQueryMapRE    = regexp.MustCompile(`@QueryMapping(?:\s*\(\s*(?:name\s*=\s*)?"([^"]*)"\s*\))?`)
	jgqlMutationMapRE = regexp.MustCompile(`@MutationMapping(?:\s*\(\s*(?:name\s*=\s*)?"([^"]*)"\s*\))?`)
	jgqlSubscMapRE    = regexp.MustCompile(`@SubscriptionMapping(?:\s*\(\s*(?:name\s*=\s*)?"([^"]*)"\s*\))?`)
	jgqlSchemaMapRE   = regexp.MustCompile(`@SchemaMapping\s*\(\s*(?:typeName\s*=\s*"([^"]*)")?`)
	jgqlDgsQueryRE    = regexp.MustCompile(`@DgsQuery(?:\s*\(\s*field\s*=\s*"([^"]*)"\s*\))?`)
	jgqlDgsMutationRE = regexp.MustCompile(`@DgsMutation(?:\s*\(\s*field\s*=\s*"([^"]*)"\s*\))?`)
	jgqlDgsSubscRE    = regexp.MustCompile(`@DgsSubscription(?:\s*\(\s*field\s*=\s*"([^"]*)"\s*\))?`)
	jgqlDgsDataRE     = regexp.MustCompile(`@DgsData\s*\(\s*parentType\s*=\s*"([^"]*)"(?:\s*,\s*field\s*=\s*"([^"]*)")?`)
	jgqlMethodRE      = regexp.MustCompile(`(?:public|protected|private)?\s*(?:[\w<>\[\],?\s]+)\s+(\w+)\s*\(`)
)

type jgqlPatternMap struct {
	re      *regexp.Regexp
	gqlType string
}

func (JavaGraphqlResolverDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "@QueryMapping") && !strings.Contains(text, "@MutationMapping") &&
		!strings.Contains(text, "@SubscriptionMapping") && !strings.Contains(text, "@SchemaMapping") &&
		!strings.Contains(text, "@BatchMapping") && !strings.Contains(text, "@DgsQuery") &&
		!strings.Contains(text, "@DgsMutation") && !strings.Contains(text, "@DgsSubscription") &&
		!strings.Contains(text, "@DgsData") {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	className := ""
	for _, line := range lines {
		if m := jgqlClassRE.FindStringSubmatch(line); m != nil {
			className = m[1]
			break
		}
	}
	if className == "" {
		return detector.EmptyResult()
	}
	classID := ctx.FilePath + ":" + className

	patterns := []jgqlPatternMap{
		{jgqlQueryMapRE, "Query"},
		{jgqlMutationMapRE, "Mutation"},
		{jgqlSubscMapRE, "Subscription"},
		{jgqlDgsQueryRE, "Query"},
		{jgqlDgsMutationRE, "Mutation"},
		{jgqlDgsSubscRE, "Subscription"},
	}

	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	for i, line := range lines {
		for _, pm := range patterns {
			m := pm.re.FindStringSubmatch(line)
			if m == nil {
				continue
			}
			fieldName := ""
			if len(m) >= 2 {
				fieldName = m[1]
			}
			if fieldName == "" {
				fieldName = jgqlFindMethod(lines, i)
			}
			if fieldName == "" {
				continue
			}
			id := ctx.FilePath + ":" + className + ":" + pm.gqlType + ":" + fieldName
			nodes = append(nodes, jgqlResolverNode(id, pm.gqlType, fieldName, className, i+1, ctx, nil))
			edges = append(edges, model.NewCodeEdge(classID+"->exposes->"+id, model.EdgeExposes, classID, id))
		}
		if m := jgqlSchemaMapRE.FindStringSubmatch(line); m != nil {
			typeName := "Unknown"
			if len(m) >= 2 && m[1] != "" {
				typeName = m[1]
			}
			methodName := jgqlFindMethod(lines, i)
			if methodName != "" {
				id := ctx.FilePath + ":" + className + ":SchemaMapping:" + typeName + "." + methodName
				nodes = append(nodes, jgqlResolverNode(id, typeName, methodName, className, i+1, ctx, nil))
				edges = append(edges, model.NewCodeEdge(classID+"->exposes->"+id, model.EdgeExposes, classID, id))
			}
		}
		if m := jgqlDgsDataRE.FindStringSubmatch(line); m != nil {
			parentType := m[1]
			fieldName := ""
			if len(m) >= 3 {
				fieldName = m[2]
			}
			if fieldName == "" {
				fieldName = jgqlFindMethod(lines, i)
			}
			if fieldName != "" {
				id := ctx.FilePath + ":" + className + ":DgsData:" + parentType + "." + fieldName
				nodes = append(nodes, jgqlResolverNode(id, parentType, fieldName, className, i+1, ctx,
					map[string]any{"framework": "dgs"}))
				edges = append(edges, model.NewCodeEdge(classID+"->exposes->"+id, model.EdgeExposes, classID, id))
			}
		}
	}

	return detector.ResultOf(nodes, edges)
}

func jgqlFindMethod(lines []string, idx int) string {
	end := idx + 4
	if end > len(lines) {
		end = len(lines)
	}
	for k := idx + 1; k < end; k++ {
		if mm := jgqlMethodRE.FindStringSubmatch(lines[k]); mm != nil {
			return mm[1]
		}
	}
	return ""
}

func jgqlResolverNode(id, gqlType, field, className string, line int, ctx *detector.Context, extra map[string]any) *model.CodeNode {
	n := model.NewCodeNode(id, model.NodeEndpoint, "GraphQL "+gqlType+"."+field)
	n.FQN = className + "." + field
	n.FilePath = ctx.FilePath
	n.LineStart = line
	n.Source = "JavaGraphqlResolverDetector"
	n.Confidence = base.RegexDetectorDefaultConfidence
	n.Properties["graphql_type"] = gqlType
	n.Properties["field"] = field
	n.Properties["protocol"] = "graphql"
	for k, v := range extra {
		n.Properties[k] = v
	}
	return n
}
