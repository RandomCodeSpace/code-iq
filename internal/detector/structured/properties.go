package structured

import (
	"regexp"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/internal/detector"
	"github.com/randomcodespace/codeiq/internal/detector/base"
	"github.com/randomcodespace/codeiq/internal/model"
)

// PropertiesDetector mirrors Java PropertiesDetector. Treats URL-shaped JDBC
// keys as DATABASE_CONNECTION nodes; everything else becomes a CONFIG_KEY.
type PropertiesDetector struct{}

func NewPropertiesDetector() *PropertiesDetector { return &PropertiesDetector{} }

const propProperties = "properties"

func (PropertiesDetector) Name() string                        { return propProperties }
func (PropertiesDetector) SupportedLanguages() []string        { return []string{propProperties} }
func (PropertiesDetector) DefaultConfidence() model.Confidence { return base.StructuredDetectorDefaultConfidence }

func init() { detector.RegisterDefault(NewPropertiesDetector()) }

const maxPropertyKeys = 200

var (
	jdbcDBTypeRE = regexp.MustCompile(`jdbc:(mysql|postgresql|sqlserver|oracle|db2|h2|sqlite|mariadb|derby|hsqldb)`)
	dbTypeLabels = map[string]string{
		"mysql":      "MySQL",
		"postgresql": "PostgreSQL",
		"sqlserver":  "SQL Server",
		"oracle":     "Oracle",
		"db2":        "DB2",
		"h2":         "H2",
		"sqlite":     "SQLite",
		"mariadb":    "MariaDB",
		"derby":      "Derby",
		"hsqldb":     "HSQLDB",
	}
	dbURLKeywords = []string{"url", "jdbc-url", "uri"}
)

func (d PropertiesDetector) Detect(ctx *detector.Context) *detector.Result {
	if ctx.ParsedData == nil {
		return detector.EmptyResult()
	}
	if base.GetString(ctx.ParsedData, "type") != propProperties {
		return detector.EmptyResult()
	}
	data := base.GetMap(ctx.ParsedData, "data")
	if len(data) == 0 {
		return detector.EmptyResult()
	}

	fp := ctx.FilePath
	fileID := "props:" + fp
	nodes := []*model.CodeNode{}
	edges := []*model.CodeEdge{}

	// CONFIG_FILE node — emit with "props:" prefix to match Java identity.
	fn := model.NewCodeNode(fileID, model.NodeConfigFile, fp)
	fn.FQN = fp
	fn.Module = ctx.ModuleName
	fn.FilePath = fp
	fn.LineStart = 1
	fn.Confidence = base.StructuredDetectorDefaultConfidence
	fn.Properties["format"] = propProperties
	nodes = append(nodes, fn)

	// Iterate keys in sorted order for determinism, capped at MAX_KEYS.
	keys := make([]string, 0, len(data))
	for k := range data {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	if len(keys) > maxPropertyKeys {
		keys = keys[:maxPropertyKeys]
	}
	for _, key := range keys {
		val := data[key]
		keyID := "props:" + fp + ":" + key
		keyLower := strings.ToLower(key)
		// Match last dotted segment vs URL-keyword set.
		lastSeg := keyLower
		if i := strings.LastIndex(keyLower, "."); i >= 0 {
			lastSeg = keyLower[i+1:]
		}
		isDBURLKey := false
		for _, kw := range dbURLKeywords {
			if lastSeg == kw || strings.Contains(lastSeg, kw) {
				isDBURLKey = true
				break
			}
		}
		valStr, _ := val.(string)
		hasDBVal := strings.Contains(valStr, "jdbc:")
		props := map[string]any{"key": key}
		if valStr != "" {
			props["value"] = valStr
		}
		var n *model.CodeNode
		if isDBURLKey && hasDBVal {
			dbType := extractDBType(valStr)
			dbLabel := dbType
			if dbLabel == "" {
				dbLabel = "database"
			}
			props["db_type"] = dbLabel
			n = model.NewCodeNode(keyID, model.NodeDatabaseConnection, dbLabel)
		} else {
			if strings.HasPrefix(key, "spring.") {
				props["spring_config"] = true
			}
			n = model.NewCodeNode(keyID, model.NodeConfigKey, key)
		}
		n.FQN = fp + ":" + key
		n.Module = ctx.ModuleName
		n.FilePath = fp
		n.Confidence = base.StructuredDetectorDefaultConfidence
		for pk, pv := range props {
			n.Properties[pk] = pv
		}
		nodes = append(nodes, n)
		edges = append(edges, model.NewCodeEdge(
			fileID+"->"+keyID, model.EdgeContains, fileID, keyID))
	}
	return detector.ResultOf(nodes, edges)
}

// extractDBType returns the friendly DB label for a JDBC URL, or "" if the
// URL doesn't match a recognized prefix.
func extractDBType(jdbcURL string) string {
	if jdbcURL == "" {
		return ""
	}
	m := jdbcDBTypeRE.FindStringSubmatch(strings.ToLower(jdbcURL))
	if len(m) < 2 {
		return ""
	}
	if label, ok := dbTypeLabels[m[1]]; ok {
		return label
	}
	return m[1]
}
