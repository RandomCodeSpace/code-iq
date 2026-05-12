// Package query implements the codeiq Go port's query-side services. It
// wraps internal/graph.Store with task-level helpers and renders the JSON-
// ready shapes the Java side's QueryService / StatsService / TopologyService
// expose. These services are read-only; mutation paths live in analyzer/.
//
// The package centres on three services:
//
//   - StatsService — pure functions over (nodes, edges) slices. Used when the
//     enrich pipeline has the full graph in heap; the serve side uses
//     graph.Store aggregations instead. Mirrors StatsService.java.
//   - Service — high-level read service backed by a graph.Store. Mirrors
//     QueryService.java (consumers / producers / callers / cycles / dead).
//   - Topology — service-topology analyses. Mirrors TopologyService.java.
package query

import (
	"bytes"
	"encoding/json"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// StatsService computes rich categorized statistics from in-memory node /
// edge slices. Stateless — the zero value is usable.
type StatsService struct{}

// OrderedMap preserves insertion order — equivalent to Java's
// LinkedHashMap. Stats JSON output relies on a deterministic top-level key
// order matching the Java side for parity diffing.
type OrderedMap struct {
	Keys   []string
	Values map[string]any
}

func newOrdered() *OrderedMap { return &OrderedMap{Values: map[string]any{}} }

// Put records key in insertion order; an overwrite keeps the original
// position so that re-assignment is non-disruptive.
func (m *OrderedMap) Put(k string, v any) {
	if _, ok := m.Values[k]; !ok {
		m.Keys = append(m.Keys, k)
	}
	m.Values[k] = v
}

// MarshalJSON emits keys in insertion order — the whole point of OrderedMap.
// Empty/zero maps emit `{}`. Nested OrderedMaps recurse correctly through
// json.Encoder's reflective path because MarshalJSON is declared on the
// pointer receiver and the package always passes *OrderedMap values around.
func (m *OrderedMap) MarshalJSON() ([]byte, error) {
	if m == nil {
		return []byte("null"), nil
	}
	var buf bytes.Buffer
	buf.WriteByte('{')
	for i, k := range m.Keys {
		if i > 0 {
			buf.WriteByte(',')
		}
		kb, err := json.Marshal(k)
		if err != nil {
			return nil, err
		}
		buf.Write(kb)
		buf.WriteByte(':')
		vb, err := json.Marshal(m.Values[k])
		if err != nil {
			return nil, err
		}
		buf.Write(vb)
	}
	buf.WriteByte('}')
	return buf.Bytes(), nil
}

// ComputeStats returns the seven-category breakdown:
// graph, languages, frameworks, infra, connections, auth, architecture.
// Order matches Java StatsService.computeStats line-for-line for parity.
func (s *StatsService) ComputeStats(nodes []*model.CodeNode, edges []*model.CodeEdge) *OrderedMap {
	out := newOrdered()
	out.Put("graph", s.computeGraph(nodes, edges))
	out.Put("languages", s.computeLanguages(nodes))
	out.Put("frameworks", s.computeFrameworks(nodes))
	out.Put("infra", s.computeInfra(nodes))
	out.Put("connections", s.computeConnections(nodes, edges))
	out.Put("auth", s.computeAuth(nodes))
	out.Put("architecture", s.computeArchitecture(nodes))
	return out
}

// ComputeCategory returns just one category. Names are matched
// case-insensitively. Returns nil for unknown categories — matches Java
// behaviour rather than returning an error envelope (the controller layer
// surfaces that as "Unknown category").
func (s *StatsService) ComputeCategory(nodes []*model.CodeNode, edges []*model.CodeEdge, category string) *OrderedMap {
	switch strings.ToLower(category) {
	case "graph":
		return s.computeGraph(nodes, edges)
	case "languages":
		return s.computeLanguages(nodes)
	case "frameworks":
		return s.computeFrameworks(nodes)
	case "infra":
		return s.computeInfra(nodes)
	case "connections":
		return s.computeConnections(nodes, edges)
	case "auth":
		return s.computeAuth(nodes)
	case "architecture":
		return s.computeArchitecture(nodes)
	default:
		return nil
	}
}

// --- Category implementations (ported from StatsService.java) ---

func (s *StatsService) computeGraph(nodes []*model.CodeNode, edges []*model.CodeEdge) *OrderedMap {
	files := map[string]struct{}{}
	for _, n := range nodes {
		if strings.TrimSpace(n.FilePath) != "" {
			files[n.FilePath] = struct{}{}
		}
	}
	counts := map[string]int{}
	for _, e := range edges {
		counts[e.Kind.String()]++
	}
	g := newOrdered()
	g.Put("nodes", len(nodes))
	g.Put("edges", len(edges))
	g.Put("files", len(files))
	g.Put("edges_by_kind", sortByValueDesc(counts))
	return g
}

func (s *StatsService) computeLanguages(nodes []*model.CodeNode) *OrderedMap {
	counts := map[string]int{}
	for _, n := range nodes {
		lang := extractLanguage(n)
		if strings.TrimSpace(lang) != "" {
			counts[lang]++
		}
	}
	return sortByValueDesc(counts)
}

func (s *StatsService) computeFrameworks(nodes []*model.CodeNode) *OrderedMap {
	counts := map[string]int{}
	for _, n := range nodes {
		fw, _ := n.Properties["framework"].(string)
		fw = strings.TrimSpace(fw)
		if fw != "" {
			counts[fw]++
		}
	}
	return sortByValueDesc(counts)
}

// dbTypeNormalize mirrors Java's DB_TYPE_NORMALIZE Map.ofEntries — display
// strings for known JDBC subprotocols and NoSQL drivers.
var dbTypeNormalize = map[string]string{
	"mysql":       "MySQL",
	"postgresql":  "PostgreSQL",
	"postgres":    "PostgreSQL",
	"sqlserver":   "SQL Server",
	"mssql":       "SQL Server",
	"oracle":      "Oracle",
	"db2":         "DB2",
	"h2":          "H2",
	"sqlite":      "SQLite",
	"mariadb":     "MariaDB",
	"derby":       "Derby",
	"hsqldb":      "HSQLDB",
	"mongo":       "MongoDB",
	"mongodb":     "MongoDB",
	"redis":       "Redis",
	"cassandra":   "Cassandra",
	"dynamodb":    "DynamoDB",
	"couchbase":   "Couchbase",
	"neo4j":       "Neo4j",
	"cockroachdb": "CockroachDB",
}

func (s *StatsService) computeInfra(nodes []*model.CodeNode) *OrderedMap {
	databases := map[string]int{}
	messaging := map[string]int{}
	cloud := map[string]int{}

	for _, n := range nodes {
		switch n.Kind {
		case model.NodeDatabaseConnection:
			if dbType := resolveDbType(n); dbType != "" {
				databases[dbType]++
			}
		case model.NodeTopic, model.NodeQueue, model.NodeMessageQueue:
			messaging[propOrLabel(n, "protocol")]++
		case model.NodeAzureResource, model.NodeInfraResource:
			cloud[propOrLabel(n, "resource_type")]++
		}
	}

	infra := newOrdered()
	infra.Put("databases", sortByValueDesc(databases))
	infra.Put("messaging", sortByValueDesc(messaging))
	infra.Put("cloud", sortByValueDesc(cloud))
	return infra
}

func (s *StatsService) computeConnections(nodes []*model.CodeNode, edges []*model.CodeEdge) *OrderedMap {
	restByMethod := map[string]int{}
	var grpcCount, wsCount int64

	for _, n := range nodes {
		switch n.Kind {
		case model.NodeEndpoint:
			protocol, _ := n.Properties["protocol"].(string)
			if strings.EqualFold(protocol, "grpc") {
				grpcCount++
				continue
			}
			method, _ := n.Properties["http_method"].(string)
			if method == "" {
				method = "UNKNOWN"
			}
			restByMethod[strings.ToUpper(method)]++
		case model.NodeWebSocketEndpoint:
			wsCount++
		}
	}

	var restTotal int64
	for _, v := range restByMethod {
		restTotal += int64(v)
	}

	rest := newOrdered()
	rest.Put("total", restTotal)
	rest.Put("by_method", sortByValueDesc(restByMethod))

	var producers, consumers int64
	for _, e := range edges {
		switch e.Kind {
		case model.EdgeProduces, model.EdgePublishes:
			producers++
		case model.EdgeConsumes, model.EdgeListens:
			consumers++
		}
	}

	conn := newOrdered()
	conn.Put("rest", rest)
	conn.Put("grpc", grpcCount)
	conn.Put("websocket", wsCount)
	conn.Put("producers", producers)
	conn.Put("consumers", consumers)
	return conn
}

func (s *StatsService) computeAuth(nodes []*model.CodeNode) *OrderedMap {
	counts := map[string]int{}
	for _, n := range nodes {
		if n.Kind == model.NodeGuard {
			authType, _ := n.Properties["auth_type"].(string)
			if authType == "" {
				authType = "unknown"
			}
			counts[authType]++
			continue
		}
		fw, _ := n.Properties["framework"].(string)
		fw = strings.TrimSpace(fw)
		if strings.HasPrefix(fw, "auth:") {
			authType := strings.TrimSpace(fw[len("auth:"):])
			if authType != "" {
				counts[authType]++
			}
		}
	}
	return sortByValueDesc(counts)
}

func (s *StatsService) computeArchitecture(nodes []*model.CodeNode) *OrderedMap {
	var classes, interfaces, abstracts, enums, annotations, modules, methods int
	for _, n := range nodes {
		switch n.Kind {
		case model.NodeClass:
			classes++
		case model.NodeInterface:
			interfaces++
		case model.NodeAbstractClass:
			abstracts++
		case model.NodeEnum:
			enums++
		case model.NodeAnnotationType:
			annotations++
		case model.NodeModule:
			modules++
		case model.NodeMethod:
			methods++
		}
	}
	arch := newOrdered()
	if classes > 0 {
		arch.Put("classes", classes)
	}
	if interfaces > 0 {
		arch.Put("interfaces", interfaces)
	}
	if abstracts > 0 {
		arch.Put("abstract_classes", abstracts)
	}
	if enums > 0 {
		arch.Put("enums", enums)
	}
	if annotations > 0 {
		arch.Put("annotation_types", annotations)
	}
	if modules > 0 {
		arch.Put("modules", modules)
	}
	if methods > 0 {
		arch.Put("methods", methods)
	}
	return arch
}

// --- Helpers ---

// extractLanguage prefers properties.language, falling back to the file
// extension lookup table. Returns "" when neither is available.
func extractLanguage(n *model.CodeNode) string {
	if lang, _ := n.Properties["language"].(string); strings.TrimSpace(lang) != "" {
		return strings.ToLower(lang)
	}
	if dot := strings.LastIndex(n.FilePath, "."); dot >= 0 {
		ext := strings.ToLower(n.FilePath[dot+1:])
		return extByLang(ext)
	}
	return ""
}

// extByLang mirrors the Java switch in StatsService.extractLanguage. The
// fallthrough returns the bare extension so unknown formats still
// contribute a non-empty bucket.
func extByLang(ext string) string {
	switch ext {
	case "java":
		return "java"
	case "kt", "kts":
		return "kotlin"
	case "py":
		return "python"
	case "js", "mjs", "cjs":
		return "javascript"
	case "ts", "tsx":
		return "typescript"
	case "go":
		return "go"
	case "rs":
		return "rust"
	case "cs":
		return "csharp"
	case "rb":
		return "ruby"
	case "scala":
		return "scala"
	case "cpp", "cc", "cxx":
		return "cpp"
	case "c", "h":
		return "c"
	case "proto":
		return "protobuf"
	case "yml", "yaml":
		return "yaml"
	case "json":
		return "json"
	case "xml":
		return "xml"
	case "toml":
		return "toml"
	case "ini", "cfg":
		return "ini"
	case "properties":
		return "properties"
	case "gradle":
		return "gradle"
	case "tf":
		return "terraform"
	case "bicep":
		return "bicep"
	case "sql":
		return "sql"
	case "md":
		return "markdown"
	case "html", "htm":
		return "html"
	case "css", "scss", "sass":
		return "css"
	case "vue":
		return "vue"
	case "svelte":
		return "svelte"
	case "jsx":
		return "jsx"
	case "sh", "bash":
		return "shell"
	}
	return ext
}

// resolveDbType returns the display-friendly DB type for a
// DATABASE_CONNECTION node. Order:
//  1. db_type property (canonicalised via dbTypeNormalize)
//  2. extract jdbc: prefix from connection_url / value / url
//  3. fall back to label, ignoring config-key labels (contain '.' or '=')
//
// Returns "" when the node looks like a false-positive config key.
func resolveDbType(n *model.CodeNode) string {
	if dbType, _ := n.Properties["db_type"].(string); strings.TrimSpace(dbType) != "" {
		return normalizeDbType(dbType)
	}
	for _, key := range []string{"connection_url", "value", "url"} {
		if v, ok := n.Properties[key].(string); ok && strings.Contains(v, "jdbc:") {
			if t := extractDbTypeFromURL(v); t != "" {
				return t
			}
		}
	}
	label := n.Label
	if label != "" && !strings.Contains(label, ".") && !strings.Contains(label, "=") {
		return normalizeDbType(label)
	}
	return ""
}

func normalizeDbType(raw string) string {
	lower := strings.TrimSpace(strings.ToLower(raw))
	// Strip "type@host" suffix from JdbcDetector ("mysql@localhost" → "mysql").
	if i := strings.IndexByte(lower, '@'); i >= 0 {
		lower = lower[:i]
	}
	if v, ok := dbTypeNormalize[lower]; ok {
		return v
	}
	return strings.TrimSpace(raw)
}

// extractDbTypeFromURL parses "jdbc:TYPE:..." into the canonicalised TYPE.
func extractDbTypeFromURL(url string) string {
	idx := strings.Index(url, "jdbc:")
	if idx < 0 {
		return ""
	}
	after := url[idx+5:]
	colon := strings.IndexByte(after, ':')
	if colon <= 0 {
		return ""
	}
	t := strings.ToLower(after[:colon])
	if v, ok := dbTypeNormalize[t]; ok {
		return v
	}
	return t
}

// propOrLabel returns properties[key] when non-blank, else node.Label, else
// "unknown". Mirrors Java's propOrLabel helper.
func propOrLabel(n *model.CodeNode, key string) string {
	if v, ok := n.Properties[key].(string); ok && strings.TrimSpace(v) != "" {
		return v
	}
	if n.Label != "" {
		return n.Label
	}
	return "unknown"
}

// sortByValueDesc projects counts into an OrderedMap sorted by value desc,
// then by key asc — deterministic regardless of map iteration order.
func sortByValueDesc(m map[string]int) *OrderedMap {
	type kv struct {
		k string
		v int
	}
	rows := make([]kv, 0, len(m))
	for k, v := range m {
		rows = append(rows, kv{k, v})
	}
	sort.Slice(rows, func(i, j int) bool {
		if rows[i].v != rows[j].v {
			return rows[i].v > rows[j].v
		}
		return rows[i].k < rows[j].k
	})
	out := newOrdered()
	for _, r := range rows {
		out.Put(r.k, r.v)
	}
	return out
}
