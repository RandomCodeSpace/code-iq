package java

import (
	"regexp"
	"strconv"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/detector"
	"github.com/randomcodespace/codeiq/go/internal/detector/base"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// RawSqlDetector mirrors Java RawSqlDetector. Extracts raw SQL strings from
// @Query annotations, JdbcTemplate calls, and EntityManager createQuery/createNativeQuery.
type RawSqlDetector struct{}

func NewRawSqlDetector() *RawSqlDetector { return &RawSqlDetector{} }

func (RawSqlDetector) Name() string                        { return "raw_sql" }
func (RawSqlDetector) SupportedLanguages() []string        { return []string{"java"} }
func (RawSqlDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewRawSqlDetector()) }

var (
	rawSqlClassRE  = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
	rawSqlQueryRE  = regexp.MustCompile(`(?s)@Query\s*\(\s*(?:value\s*=\s*)?"([^"\\]*(?:\\.[^"\\]*)*)"`)
	rawSqlNativeRE = regexp.MustCompile(`nativeQuery\s*=\s*true`)
	rawSqlJdbcRE   = regexp.MustCompile(`(?s)(?:jdbcTemplate|namedParameterJdbcTemplate|JdbcTemplate)\.(?:query|queryForObject|queryForList|queryForMap|update|execute|batchUpdate)\s*\(\s*"([^"\\]*(?:\\.[^"\\]*)*)"`)
	rawSqlEmRE     = regexp.MustCompile(`(?s)(?:entityManager|em)\.(?:createNativeQuery|createQuery)\s*\(\s*"([^"\\]*(?:\\.[^"\\]*)*)"`)
	rawSqlTableRE  = regexp.MustCompile(`(?i)\b(?:FROM|JOIN|INTO|UPDATE|TABLE)\s+(\w+)`)
)

func (RawSqlDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "@Query") && !strings.Contains(text, "jdbcTemplate") &&
		!strings.Contains(text, "JdbcTemplate") && !strings.Contains(text, "createNativeQuery") &&
		!strings.Contains(text, "createQuery") {
		return detector.EmptyResult()
	}

	className := "Unknown"
	if m := rawSqlClassRE.FindStringSubmatch(text); m != nil {
		className = m[1]
	}

	var nodes []*model.CodeNode

	for _, m := range rawSqlQueryRE.FindAllStringSubmatchIndex(text, -1) {
		q := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		windowEnd := m[1] + 50
		if windowEnd > len(text) {
			windowEnd = len(text)
		}
		isNative := rawSqlNativeRE.MatchString(text[m[0]:windowEnd])
		nodes = append(nodes, rawSqlNode(ctx, className, "query", line, q,
			[]string{"@Query"}, map[string]any{
				"native": isNative,
				"source": "annotation",
				"tables": rawSqlTables(q),
			}))
	}
	for _, m := range rawSqlJdbcRE.FindAllStringSubmatchIndex(text, -1) {
		q := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		nodes = append(nodes, rawSqlNode(ctx, className, "jdbc", line, q,
			nil, map[string]any{
				"native": true,
				"source": "jdbc_template",
				"tables": rawSqlTables(q),
			}))
	}
	for _, m := range rawSqlEmRE.FindAllStringSubmatchIndex(text, -1) {
		q := text[m[2]:m[3]]
		line := base.FindLineNumber(text, m[0])
		start := m[0] - 30
		if start < 0 {
			start = 0
		}
		end := m[0] + 20
		if end > len(text) {
			end = len(text)
		}
		isNative := strings.Contains(text[start:end], "createNativeQuery")
		nodes = append(nodes, rawSqlNode(ctx, className, "em", line, q,
			nil, map[string]any{
				"native": isNative,
				"source": "entity_manager",
				"tables": rawSqlTables(q),
			}))
	}

	return detector.ResultOf(nodes, nil)
}

func rawSqlNode(ctx *detector.Context, class, kind string, line int, q string, anns []string, props map[string]any) *model.CodeNode {
	label := q
	if len(label) > 80 {
		label = label[:80] + "..."
	}
	id := ctx.FilePath + ":" + class + ":" + kind + ":L" + strconv.Itoa(line)
	n := model.NewCodeNode(id, model.NodeQuery, label)
	n.FQN = class + "." + kind + "@L" + strconv.Itoa(line)
	n.FilePath = ctx.FilePath
	n.LineStart = line
	n.Source = "RawSqlDetector"
	n.Confidence = base.RegexDetectorDefaultConfidence
	n.Annotations = append(n.Annotations, anns...)
	n.Properties["query"] = q
	for k, v := range props {
		n.Properties[k] = v
	}
	return n
}

func rawSqlTables(q string) []string {
	var out []string
	for _, m := range rawSqlTableRE.FindAllStringSubmatch(q, -1) {
		out = append(out, m[1])
	}
	return out
}
