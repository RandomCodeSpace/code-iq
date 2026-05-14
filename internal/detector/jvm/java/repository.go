package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// RepositoryDetector mirrors Java RepositoryDetector regex tier.
type RepositoryDetector struct{}

func NewRepositoryDetector() *RepositoryDetector { return &RepositoryDetector{} }

func (RepositoryDetector) Name() string                 { return "spring_repository" }
func (RepositoryDetector) SupportedLanguages() []string { return []string{"java"} }
func (RepositoryDetector) DefaultConfidence() model.Confidence {
	return base.RegexDetectorDefaultConfidence
}

func init() { detector.RegisterDefault(NewRepositoryDetector()) }

var (
	repoExtendsRE = regexp.MustCompile(
		`interface\s+(\w+)\s+extends\s+((?:JpaRepository|CrudRepository|PagingAndSortingRepository|ReactiveCrudRepository|MongoRepository|ElasticsearchRepository|R2dbcRepository|JpaSpecificationExecutor)\w*)(?:<\s*(\w+)\s*,\s*[\w<>]+\s*>)?`,
	)
	repoAnnoRE        = regexp.MustCompile(`@Repository`)
	repoInterfaceRE   = regexp.MustCompile(`interface\s+(\w+)`)
	repoGenericRE     = regexp.MustCompile(`<\s*(\w+)\s*,`)
	repoQueryRE       = regexp.MustCompile(`@Query\s*\(\s*(?:value\s*=\s*)?"([^"]+)"`)
	repoQueryMethodRE = regexp.MustCompile(`(?:public\s+)?(?:[\w<>\[\],?\s]+)\s+(\w+)\s*\(`)
)

func (d RepositoryDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}

	hasRepoAnno := repoAnnoRE.MatchString(text)
	extendsMatch := repoExtendsRE.FindStringSubmatch(text)
	hasExtends := extendsMatch != nil

	if !hasExtends && !hasRepoAnno {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	var nodes []*model.CodeNode
	var edges []*model.CodeEdge

	var interfaceName, entityType, parentRepo string
	interfaceLine := 0
	if hasExtends {
		interfaceName = extendsMatch[1]
		parentRepo = extendsMatch[2]
		if len(extendsMatch) > 3 {
			entityType = extendsMatch[3]
		}
		for i, line := range lines {
			if strings.Contains(line, interfaceName) && strings.Contains(line, "interface") {
				interfaceLine = i + 1
				break
			}
		}
	} else {
		for i, line := range lines {
			if m := repoInterfaceRE.FindStringSubmatch(line); m != nil {
				interfaceName = m[1]
				interfaceLine = i + 1
				if gm := repoGenericRE.FindStringSubmatch(line); gm != nil {
					entityType = gm[1]
				}
				break
			}
		}
	}

	if interfaceName == "" {
		return detector.EmptyResult()
	}

	repoID := ctx.FilePath + ":" + interfaceName
	n := model.NewCodeNode(repoID, model.NodeRepository, interfaceName)
	n.FQN = interfaceName
	n.FilePath = ctx.FilePath
	n.LineStart = interfaceLine
	n.Source = "RepositoryDetector"
	n.Properties["framework"] = "spring_boot"
	if parentRepo != "" {
		n.Properties["extends"] = parentRepo
	}
	if entityType != "" {
		n.Properties["entity_type"] = entityType
	}
	if hasRepoAnno {
		n.Annotations = append(n.Annotations, "@Repository")
	}

	// @Query methods
	var customQueries []map[string]string
	for i, line := range lines {
		qm := repoQueryRE.FindStringSubmatch(line)
		if qm == nil {
			continue
		}
		queryStr := qm[1]
		var methodName string
		for k := i + 1; k < min0(i+4, len(lines)); k++ {
			if mm := repoQueryMethodRE.FindStringSubmatch(lines[k]); mm != nil {
				methodName = mm[1]
				break
			}
		}
		if methodName == "" {
			methodName = "unknown"
		}
		customQueries = append(customQueries, map[string]string{
			"query":  queryStr,
			"method": methodName,
		})
	}
	if len(customQueries) > 0 {
		n.Properties["custom_queries"] = customQueries
	}
	nodes = append(nodes, n)

	if entityType != "" {
		e := model.NewCodeEdge(repoID+"->queries->*:"+entityType, model.EdgeQueries, repoID, "*:"+entityType)
		edges = append(edges, e)
	}

	return detector.ResultOf(nodes, edges)
}
