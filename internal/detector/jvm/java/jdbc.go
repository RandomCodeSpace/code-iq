package java

import (
	"regexp"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// JdbcDetector mirrors Java JdbcDetector. Detects JDBC connections via
// DriverManager.getConnection, JdbcTemplate/NamedParameterJdbcTemplate/JdbcClient
// fields, DataSource bean definitions, spring.datasource.url, and standalone
// JDBC URL strings.
type JdbcDetector struct{}

func NewJdbcDetector() *JdbcDetector { return &JdbcDetector{} }

func (JdbcDetector) Name() string                        { return "jdbc" }
func (JdbcDetector) SupportedLanguages() []string        { return []string{"java"} }
func (JdbcDetector) DefaultConfidence() model.Confidence { return base.RegexDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewJdbcDetector()) }

var (
	jdbcClassRE      = regexp.MustCompile(`(?:public\s+)?class\s+(\w+)`)
	jdbcDriverMgrRE  = regexp.MustCompile(`DriverManager\s*\.\s*getConnection\s*\(\s*"(jdbc:[^"]+)"`)
	jdbcTemplateRE   = regexp.MustCompile(`(?:private|protected|public|final)\s+(?:final\s+)?(JdbcTemplate|NamedParameterJdbcTemplate|JdbcClient)\s+\w+`)
	jdbcDsBeanRE     = regexp.MustCompile(`(?:@Bean|DataSource)\s*(?:\(|\.)`)
	jdbcSpringDsRE   = regexp.MustCompile(`spring\.datasource\.url\s*=\s*(jdbc:[^\s]+)`)
	jdbcURLRE        = regexp.MustCompile(`jdbc:(mysql|postgresql|sqlserver|oracle|db2|h2|sqlite|mariadb)(?::(?:thin:)?(?:@)?)?(?://([^/"'\s;?]+))?`)
	jdbcStringRE     = regexp.MustCompile(`"(jdbc:[^"]+)"`)
)

func (JdbcDetector) Detect(ctx *detector.Context) *detector.Result {
	text := ctx.Content
	if text == "" {
		return detector.EmptyResult()
	}
	if !strings.Contains(text, "JdbcTemplate") && !strings.Contains(text, "DriverManager") &&
		!strings.Contains(text, "DataSource") && !strings.Contains(text, "NamedParameterJdbcTemplate") &&
		!strings.Contains(text, "JdbcClient") {
		return detector.EmptyResult()
	}

	lines := strings.Split(text, "\n")
	className := ""
	for _, line := range lines {
		if m := jdbcClassRE.FindStringSubmatch(line); m != nil {
			className = m[1]
			break
		}
	}
	if className == "" {
		return detector.EmptyResult()
	}
	classID := ctx.FilePath + ":" + className

	var nodes []*model.CodeNode
	var edges []*model.CodeEdge
	seen := map[string]bool{}

	for i, line := range lines {
		if m := jdbcDriverMgrRE.FindStringSubmatch(line); m != nil {
			url := m[1]
			props := parseJdbcURL(url)
			dbType := stringOr(props, "db_type", "unknown")
			host := stringOr(props, "host", "unknown")
			id := "db:" + dbType + ":" + host
			jdbcAddDB(ctx, id, dbType+"@"+host, i+1, props, seen, &nodes)
			edges = append(edges, model.NewCodeEdge(classID+"->connects_to->"+id, model.EdgeConnectsTo, classID, id))
		}
	}

	for i, line := range lines {
		if m := jdbcTemplateRE.FindStringSubmatch(line); m != nil {
			templateType := m[1]
			id := ctx.FilePath + ":jdbc:" + className
			props := map[string]any{"template_type": templateType}
			jdbcAddDB(ctx, id, className+" ("+templateType+")", i+1, props, seen, &nodes)
			edges = append(edges, model.NewCodeEdge(classID+"->connects_to->"+id, model.EdgeConnectsTo, classID, id))
		}
	}

	for i, line := range lines {
		if jdbcDsBeanRE.FindStringIndex(line) != nil {
			id := ctx.FilePath + ":jdbc:" + className
			jdbcAddDB(ctx, id, className+" (DataSource)", i+1, map[string]any{"datasource": true}, seen, &nodes)
		}
	}

	if strings.Contains(text, "spring.datasource") {
		for i, line := range lines {
			if m := jdbcSpringDsRE.FindStringSubmatch(line); m != nil {
				url := m[1]
				props := parseJdbcURL(url)
				dbType := stringOr(props, "db_type", "unknown")
				host := stringOr(props, "host", "unknown")
				id := "db:" + dbType + ":" + host
				jdbcAddDB(ctx, id, dbType+"@"+host, i+1, props, seen, &nodes)
			}
		}
	}

	for i, line := range lines {
		if strings.Contains(line, "DriverManager") || strings.Contains(line, "spring.datasource") {
			continue
		}
		for _, m := range jdbcStringRE.FindAllStringSubmatch(line, -1) {
			url := m[1]
			props := parseJdbcURL(url)
			dbType := stringOr(props, "db_type", "unknown")
			host := stringOr(props, "host", "unknown")
			id := "db:" + dbType + ":" + host
			jdbcAddDB(ctx, id, dbType+"@"+host, i+1, props, seen, &nodes)
			edges = append(edges, model.NewCodeEdge(classID+"->connects_to->"+id, model.EdgeConnectsTo, classID, id))
		}
	}

	return detector.ResultOf(nodes, edges)
}

func parseJdbcURL(url string) map[string]any {
	props := map[string]any{"connection_url": url}
	if m := jdbcURLRE.FindStringSubmatch(url); m != nil {
		props["db_type"] = m[1]
		if m[2] != "" {
			props["host"] = m[2]
		}
	}
	return props
}

func jdbcAddDB(ctx *detector.Context, id, label string, line int, props map[string]any, seen map[string]bool, nodes *[]*model.CodeNode) {
	if seen[id] {
		return
	}
	seen[id] = true
	n := model.NewCodeNode(id, model.NodeDatabaseConnection, label)
	n.FilePath = ctx.FilePath
	n.LineStart = line
	n.Source = "JdbcDetector"
	n.Confidence = base.RegexDetectorDefaultConfidence
	for k, v := range props {
		n.Properties[k] = v
	}
	*nodes = append(*nodes, n)
}

func stringOr(props map[string]any, key, fallback string) string {
	if v, ok := props[key].(string); ok {
		return v
	}
	return fallback
}
