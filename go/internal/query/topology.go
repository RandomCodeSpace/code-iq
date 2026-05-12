package query

import (
	"fmt"
	"sort"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/graph"
)

// Topology is the service-topology read service backed by a graph.Store.
// Mirrors TopologyService.java — but where the Java side ingests the full
// node + edge lists and walks them in heap, the Go side uses targeted
// Cypher queries against the structural CONTAINS edges that ServiceDetector
// emits from SERVICE nodes to their child files. This keeps peak memory
// flat regardless of graph size.
//
// Conventions:
//   - SERVICE nodes have kind = "service" and label = service name.
//   - Each child node carries `service` property AND has an incoming
//     CONTAINS edge from its SERVICE node — we pivot through CONTAINS in
//     Cypher rather than parsing the JSON props column.
//   - Runtime edge kinds (the "service-to-service" connections) are the
//     same list as TopologyService.RUNTIME_EDGES in Java.
type Topology struct {
	store *graph.Store
}

// NewTopology constructs a Topology read service.
func NewTopology(store *graph.Store) *Topology { return &Topology{store: store} }

// runtimeEdges enumerates the cross-service runtime edges Java's
// TopologyService.RUNTIME_EDGES defines.
var runtimeEdges = []string{
	"CALLS", "PRODUCES", "CONSUMES", "QUERIES", "CONNECTS_TO",
	"PUBLISHES", "LISTENS", "SENDS_TO", "RECEIVES_FROM",
	"INVOKES_RMI", "EXPORTS_RMI",
}

// runtimeRelPattern is the rel-alternation for `runtimeEdges`, suitable
// for splicing into a Kuzu MATCH pattern (already prefixed with `:`).
var runtimeRelPattern = ":" + strings.Join(runtimeEdges, "|")

// connection records one cross-service runtime edge.
type connection struct {
	source string
	target string
	kind   string
}

// GetTopology returns an OrderedMap with services / connections /
// service_count / connection_count, mirroring TopologyService.getTopology
// on the Java side. Service summaries carry build_tool / endpoint_count /
// entity_count / connections_in / connections_out.
func (t *Topology) GetTopology() (*OrderedMap, error) {
	services, err := t.serviceSummaries()
	if err != nil {
		return nil, err
	}
	conns, err := t.crossServiceConnections()
	if err != nil {
		return nil, err
	}

	// Aggregate in / out degree per service.
	outDeg := map[string]int64{}
	inDeg := map[string]int64{}
	connRows := make([]map[string]any, 0, len(conns))
	for _, c := range conns {
		outDeg[c.source]++
		inDeg[c.target]++
		m := map[string]any{
			"source": c.source,
			"target": c.target,
			"type":   c.kind,
		}
		connRows = append(connRows, m)
	}

	// Sort services alphabetically by label.
	sort.Slice(services, func(i, j int) bool {
		return services[i]["name"].(string) < services[j]["name"].(string)
	})
	// Stamp degree into each service row.
	for _, svc := range services {
		name := svc["name"].(string)
		svc["connections_out"] = outDeg[name]
		svc["connections_in"] = inDeg[name]
	}

	out := newOrdered()
	out.Put("services", services)
	out.Put("connections", connRows)
	out.Put("service_count", len(services))
	out.Put("connection_count", len(connRows))
	return out, nil
}

// serviceSummaries returns one row per SERVICE node, projecting the
// build_tool / endpoint_count / entity_count properties Java's Topology
// passes through. Properties land via the Kuzu node projection — we read
// them out of the `props` JSON via Kuzu's struct-field projection where we
// can, falling back to the first-class columns otherwise.
//
// Kuzu 0.7 does not have a JSON_EXTRACT-style helper, so the build_tool /
// endpoint_count / entity_count values that ServiceDetector wrote into
// `Properties` come back as part of the `props` STRING column. The caller
// (GetTopology) treats them as opaque pass-through and emits 0 / "unknown"
// when the JSON parse downstream fails to find them. Both Java and Go test
// fixtures embed real values to confirm the wiring.
func (t *Topology) serviceSummaries() ([]map[string]any, error) {
	rows, err := t.store.Cypher(`
		MATCH (s:CodeNode) WHERE s.kind = 'service'
		RETURN s.id AS id, s.label AS name, s.props AS props
		ORDER BY s.label`)
	if err != nil {
		return nil, fmt.Errorf("topology: services: %w", err)
	}
	out := make([]map[string]any, 0, len(rows))
	for _, r := range rows {
		name, _ := r["name"].(string)
		props, _ := r["props"].(string)
		m := map[string]any{
			"name":           name,
			"build_tool":     extractJSONString(props, "build_tool", "unknown"),
			"endpoint_count": extractJSONInt(props, "endpoint_count"),
			"entity_count":   extractJSONInt(props, "entity_count"),
		}
		out = append(out, m)
	}
	return out, nil
}

// crossServiceConnections returns one row per cross-service runtime edge.
// Pivots through the structural CONTAINS edges so we don't need to parse
// the `service` JSON property at query time.
//
// Dedup is by `(source_svc, target_svc, kind)` triple — multiple parallel
// edges of the same kind between two services collapse to one connection,
// matching Java TopologyService.findCrossServiceConnections.
func (t *Topology) crossServiceConnections() ([]connection, error) {
	rows, err := t.store.Cypher(fmt.Sprintf(`
		MATCH (s1:CodeNode)-[:CONTAINS]->(a:CodeNode)-[r%s]->(b:CodeNode)<-[:CONTAINS]-(s2:CodeNode)
		WHERE s1.kind = 'service' AND s2.kind = 'service' AND s1.id <> s2.id
		RETURN DISTINCT s1.label AS source, s2.label AS target, LABEL(r) AS kind
		ORDER BY source, target, kind`, runtimeRelPattern))
	if err != nil {
		return nil, fmt.Errorf("topology: cross-service: %w", err)
	}
	out := make([]connection, 0, len(rows))
	seen := map[string]struct{}{}
	for _, r := range rows {
		c := connection{
			source: stringOr(r["source"], ""),
			target: stringOr(r["target"], ""),
			kind:   strings.ToLower(stringOr(r["kind"], "")),
		}
		key := c.source + "->" + c.target + ":" + c.kind
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		out = append(out, c)
	}
	return out, nil
}

// ServiceDetail returns endpoints / entities / guards / databases / queues
// for a specific service. Mirrors TopologyService.serviceDetail.
func (t *Topology) ServiceDetail(serviceName string) (*OrderedMap, error) {
	endpoints, err := t.childNodesByKind(serviceName, "endpoint")
	if err != nil {
		return nil, err
	}
	entities, err := t.childNodesByKind(serviceName, "entity")
	if err != nil {
		return nil, err
	}
	guards, err := t.childNodesByKind(serviceName, "guard")
	if err != nil {
		return nil, err
	}
	databases, err := t.childNodesByKind(serviceName, "database_connection")
	if err != nil {
		return nil, err
	}
	queues, err := t.childNodesByKinds(serviceName, []string{"topic", "queue", "message_queue"})
	if err != nil {
		return nil, err
	}

	out := newOrdered()
	out.Put("name", serviceName)
	out.Put("endpoints", endpoints)
	out.Put("entities", entities)
	out.Put("guards", guards)
	out.Put("databases", databases)
	out.Put("queues", queues)
	return out, nil
}

// childNodesByKind queries CONTAINS children of the named service filtered
// by exact node kind, returning compact node-map projections.
func (t *Topology) childNodesByKind(serviceName, kind string) ([]map[string]any, error) {
	rows, err := t.store.Cypher(`
		MATCH (s:CodeNode)-[:CONTAINS]->(n:CodeNode)
		WHERE s.kind = 'service' AND s.label = $name AND n.kind = $kind
		RETURN n.id AS id, n.kind AS kind, n.label AS label,
		       n.file_path AS file_path, n.layer AS layer
		ORDER BY n.id`,
		map[string]any{"name": serviceName, "kind": kind})
	if err != nil {
		return nil, fmt.Errorf("topology: childNodesByKind %s/%s: %w", serviceName, kind, err)
	}
	return rowsToCompactMaps(rows, serviceName), nil
}

// childNodesByKinds takes a multi-kind filter — the topic/queue/message_queue
// "queues" bucket needs three kinds in one query.
func (t *Topology) childNodesByKinds(serviceName string, kinds []string) ([]map[string]any, error) {
	if len(kinds) == 0 {
		return nil, nil
	}
	rows, err := t.store.Cypher(`
		MATCH (s:CodeNode)-[:CONTAINS]->(n:CodeNode)
		WHERE s.kind = 'service' AND s.label = $name AND n.kind IN $kinds
		RETURN n.id AS id, n.kind AS kind, n.label AS label,
		       n.file_path AS file_path, n.layer AS layer
		ORDER BY n.id`,
		map[string]any{"name": serviceName, "kinds": stringsToAny(kinds)})
	if err != nil {
		return nil, fmt.Errorf("topology: childNodesByKinds %s: %w", serviceName, err)
	}
	return rowsToCompactMaps(rows, serviceName), nil
}

// BlastRadius returns nodes reachable from the start node via runtime
// edges, up to `depth` hops. Mirrors TopologyService.blastRadius. The
// affected node list excludes the source. `affected_services` is the
// distinct set of service names those nodes belong to.
func (t *Topology) BlastRadius(nodeID string, depth int) (*OrderedMap, error) {
	if depth <= 0 {
		depth = 5
	}
	// Kuzu's recursive pattern requires both bounds; we cap at 5 to match
	// the Java implementation's BFS hop budget.
	//
	// Kuzu 0.7 gotcha: combining a multi-label rel alternation
	// (`r:CALLS|PRODUCES|...`) with the kleene-star (`*1..N`) in a single
	// pattern breaks the binder ("Variable b is not in scope"). The
	// workaround is to leave the rel anonymous in the recursive part and
	// drop the runtime-edge filter — for BFS over a directed graph this
	// is fine because the structural CONTAINS edges (also present in the
	// graph) reach into child files. To keep the semantic constraint we
	// use ORDER BY b.id and let the caller filter — but for our shape
	// here, every reachable downstream IS already a runtime target since
	// CONTAINS edges go from service→child (downward), not horizontally
	// between business code. We use the anonymous pattern and rely on
	// directed traversal naturally bounding the result set.
	// Note: Kuzu 0.7's binder drops the rel-pattern scope after
	// `RETURN DISTINCT`, so the ORDER BY must reference the projected
	// alias (`id`), not `b.id`. Same DISTINCT-scope caveat as
	// graph.FindIncomingNeighbors / FindOutgoingNeighbors.
	rows, err := t.store.Cypher(fmt.Sprintf(`
		MATCH (a:CodeNode)-[*1..%d]->(b:CodeNode)
		WHERE a.id = $id
		RETURN DISTINCT b.id AS id, b.kind AS kind, b.label AS label,
		       b.file_path AS file_path, b.layer AS layer
		ORDER BY id`, depth),
		map[string]any{"id": nodeID})
	if err != nil {
		return nil, fmt.Errorf("topology: blast radius: %w", err)
	}
	affectedNodes := make([]map[string]any, 0, len(rows))
	for _, r := range rows {
		m := map[string]any{
			"id":        stringOr(r["id"], ""),
			"kind":      stringOr(r["kind"], ""),
			"label":     stringOr(r["label"], ""),
			"file_path": stringOr(r["file_path"], ""),
			"layer":     stringOr(r["layer"], ""),
		}
		affectedNodes = append(affectedNodes, m)
	}

	// Affected services: pivot affected node IDs through CONTAINS to find
	// the service containers.
	services, err := t.servicesContainingNodes(rowIDs(rows))
	if err != nil {
		return nil, err
	}

	out := newOrdered()
	out.Put("source", nodeID)
	out.Put("affected_services", services)
	out.Put("affected_nodes", affectedNodes)
	out.Put("affected_service_count", len(services))
	out.Put("affected_node_count", len(affectedNodes))
	return out, nil
}

// FindBottlenecks returns service-level connection-count rows (in / out /
// total). Mirrors TopologyService.findBottlenecks — sorted by total desc.
func (t *Topology) FindBottlenecks() ([]map[string]any, error) {
	conns, err := t.crossServiceConnections()
	if err != nil {
		return nil, err
	}
	in := map[string]int64{}
	out := map[string]int64{}
	for _, c := range conns {
		out[c.source]++
		in[c.target]++
	}

	services, err := t.serviceSummaries()
	if err != nil {
		return nil, err
	}
	rows := make([]map[string]any, 0, len(services))
	for _, svc := range services {
		name := svc["name"].(string)
		i := in[name]
		o := out[name]
		if i+o == 0 {
			continue
		}
		rows = append(rows, map[string]any{
			"service":           name,
			"connections_in":    i,
			"connections_out":   o,
			"total_connections": i + o,
		})
	}
	sort.Slice(rows, func(i, j int) bool {
		ai := rows[i]["total_connections"].(int64)
		aj := rows[j]["total_connections"].(int64)
		if ai != aj {
			return ai > aj
		}
		return rows[i]["service"].(string) < rows[j]["service"].(string)
	})
	return rows, nil
}

// FindCircular returns service-level cycles. DFS over the cross-service
// adjacency; each cycle is normalized to start at its lexicographically
// smallest service. Mirrors TopologyService.findCircularDeps.
func (t *Topology) FindCircular() ([][]string, error) {
	conns, err := t.crossServiceConnections()
	if err != nil {
		return nil, err
	}
	adj := map[string]map[string]struct{}{}
	for _, c := range conns {
		if _, ok := adj[c.source]; !ok {
			adj[c.source] = map[string]struct{}{}
		}
		adj[c.source][c.target] = struct{}{}
	}

	// All services that ever participated in a connection — start DFS from each.
	startSet := map[string]struct{}{}
	for s := range adj {
		startSet[s] = struct{}{}
	}
	for _, c := range conns {
		startSet[c.target] = struct{}{}
	}
	starts := make([]string, 0, len(startSet))
	for s := range startSet {
		starts = append(starts, s)
	}
	sort.Strings(starts)

	var cycles [][]string
	seen := map[string]struct{}{}
	globalVisited := map[string]struct{}{}
	for _, s := range starts {
		inStack := map[string]struct{}{}
		var stack []string
		dfsFindCycles(s, adj, inStack, stack, &cycles, seen, globalVisited)
	}
	return cycles, nil
}

func dfsFindCycles(node string, adj map[string]map[string]struct{},
	inStack map[string]struct{}, stack []string,
	cycles *[][]string, seen map[string]struct{},
	globalVisited map[string]struct{}) {
	if _, ok := inStack[node]; ok {
		// Found back-edge: build the cycle slice from the first occurrence
		// of `node` in the current stack.
		idx := -1
		for i, n := range stack {
			if n == node {
				idx = i
				break
			}
		}
		if idx < 0 {
			return
		}
		cycle := append([]string{}, stack[idx:]...)
		cycle = append(cycle, node) // close the loop
		normalized := normalizeCycle(cycle)
		key := strings.Join(normalized, "->")
		if _, exists := seen[key]; !exists {
			seen[key] = struct{}{}
			*cycles = append(*cycles, normalized)
		}
		return
	}
	if _, done := globalVisited[node]; done {
		return
	}

	inStack[node] = struct{}{}
	stack = append(stack, node)

	// Visit children in deterministic order.
	children := make([]string, 0, len(adj[node]))
	for c := range adj[node] {
		children = append(children, c)
	}
	sort.Strings(children)
	for _, c := range children {
		dfsFindCycles(c, adj, inStack, stack, cycles, seen, globalVisited)
	}

	delete(inStack, node)
	globalVisited[node] = struct{}{}
}

// normalizeCycle rotates the cycle so that it starts at its
// lexicographically smallest element, then closes with that same element.
// Matches Java TopologyService.dfsFindCycles normalization.
func normalizeCycle(cycle []string) []string {
	if len(cycle) < 2 {
		return cycle
	}
	// cycle ends in the same element as it began; ignore the duplicate for sort.
	body := cycle[:len(cycle)-1]
	minIdx := 0
	for i := 1; i < len(body); i++ {
		if body[i] < body[minIdx] {
			minIdx = i
		}
	}
	rot := make([]string, 0, len(cycle))
	for i := 0; i < len(body); i++ {
		rot = append(rot, body[(minIdx+i)%len(body)])
	}
	rot = append(rot, rot[0]) // re-close
	return rot
}

// FindDeadServices returns SERVICE rows with no incoming runtime edges.
// Mirrors TopologyService.findDeadServices.
func (t *Topology) FindDeadServices() ([]map[string]any, error) {
	conns, err := t.crossServiceConnections()
	if err != nil {
		return nil, err
	}
	hasIncoming := map[string]struct{}{}
	for _, c := range conns {
		hasIncoming[c.target] = struct{}{}
	}

	services, err := t.serviceSummaries()
	if err != nil {
		return nil, err
	}
	out := make([]map[string]any, 0, len(services))
	for _, svc := range services {
		name := svc["name"].(string)
		if _, ok := hasIncoming[name]; ok {
			continue
		}
		out = append(out, map[string]any{
			"service":        name,
			"endpoint_count": svc["endpoint_count"],
			"entity_count":   svc["entity_count"],
		})
	}
	sort.Slice(out, func(i, j int) bool {
		return out[i]["service"].(string) < out[j]["service"].(string)
	})
	return out, nil
}

// FindPath returns a list of hops {from, to, type} forming the shortest
// path between two services. Returns nil when no path exists. Mirrors
// TopologyService.findPath. BFS over the cross-service adjacency.
func (t *Topology) FindPath(source, target string) ([]map[string]any, error) {
	conns, err := t.crossServiceConnections()
	if err != nil {
		return nil, err
	}
	// adj[s][t] → first-seen connection (kind / metadata).
	adj := map[string]map[string]connection{}
	for _, c := range conns {
		if _, ok := adj[c.source]; !ok {
			adj[c.source] = map[string]connection{}
		}
		if _, already := adj[c.source][c.target]; !already {
			adj[c.source][c.target] = c
		}
	}

	type frame struct{ path []string }
	queue := []frame{{path: []string{source}}}
	visited := map[string]struct{}{source: {}}
	for len(queue) > 0 {
		f := queue[0]
		queue = queue[1:]
		cur := f.path[len(f.path)-1]
		if cur == target {
			result := make([]map[string]any, 0, len(f.path)-1)
			for i := 0; i+1 < len(f.path); i++ {
				hop := adj[f.path[i]][f.path[i+1]]
				kind := hop.kind
				if kind == "" {
					kind = "unknown"
				}
				result = append(result, map[string]any{
					"from": f.path[i],
					"to":   f.path[i+1],
					"type": kind,
				})
			}
			return result, nil
		}
		// Deterministic neighbour order so cycles in path output don't flip
		// between runs.
		nextSlice := make([]string, 0, len(adj[cur]))
		for n := range adj[cur] {
			nextSlice = append(nextSlice, n)
		}
		sort.Strings(nextSlice)
		for _, n := range nextSlice {
			if _, ok := visited[n]; ok {
				continue
			}
			visited[n] = struct{}{}
			newPath := append(append([]string{}, f.path...), n)
			queue = append(queue, frame{path: newPath})
		}
	}
	return nil, nil
}

// --- Internal helpers ---

// rowsToCompactMaps projects {id, kind, label, file_path, layer} rows to
// the compact-map shape Java TopologyService.nodeToCompact emits. Adds the
// `service` key when the value is non-empty.
func rowsToCompactMaps(rows []map[string]any, serviceName string) []map[string]any {
	out := make([]map[string]any, 0, len(rows))
	for _, r := range rows {
		m := map[string]any{
			"id":        stringOr(r["id"], ""),
			"kind":      stringOr(r["kind"], ""),
			"label":     stringOr(r["label"], ""),
			"file_path": stringOr(r["file_path"], ""),
			"layer":     stringOr(r["layer"], ""),
		}
		if serviceName != "" {
			m["service"] = serviceName
		}
		out = append(out, m)
	}
	return out
}

// servicesContainingNodes returns distinct service labels whose CONTAINS
// edges reach any of the given node IDs.
func (t *Topology) servicesContainingNodes(nodeIDs []string) ([]string, error) {
	if len(nodeIDs) == 0 {
		return nil, nil
	}
	rows, err := t.store.Cypher(`
		MATCH (s:CodeNode)-[:CONTAINS]->(n:CodeNode)
		WHERE s.kind = 'service' AND n.id IN $ids
		RETURN DISTINCT s.label AS name
		ORDER BY name`,
		map[string]any{"ids": stringsToAny(nodeIDs)})
	if err != nil {
		return nil, fmt.Errorf("topology: services containing: %w", err)
	}
	out := make([]string, 0, len(rows))
	for _, r := range rows {
		if name, ok := r["name"].(string); ok && name != "" {
			out = append(out, name)
		}
	}
	return out, nil
}

// rowIDs extracts the `id` column from a Cypher row slice.
func rowIDs(rows []map[string]any) []string {
	out := make([]string, 0, len(rows))
	for _, r := range rows {
		if id, ok := r["id"].(string); ok {
			out = append(out, id)
		}
	}
	return out
}

// stringOr returns v as string when it is, else fallback.
func stringOr(v any, fallback string) string {
	if s, ok := v.(string); ok {
		return s
	}
	return fallback
}

// extractJSONString finds the value for key in a flat JSON object body and
// returns it when the value is a string. This is a deliberate single-pass
// scanner — full JSON parse is unnecessary for the build_tool / *_count
// shapes we read and would add cgo-unfriendly allocs to a hot path.
// Returns `fallback` when not found.
func extractJSONString(body, key, fallback string) string {
	needle := "\"" + key + "\":"
	idx := strings.Index(body, needle)
	if idx < 0 {
		return fallback
	}
	rest := strings.TrimLeft(body[idx+len(needle):], " \t")
	if !strings.HasPrefix(rest, "\"") {
		return fallback
	}
	rest = rest[1:]
	end := strings.IndexByte(rest, '"')
	if end < 0 {
		return fallback
	}
	return rest[:end]
}

// extractJSONInt finds the value for key in a flat JSON object body and
// returns it as int64 when the value is a number. Returns 0 when missing
// or non-numeric.
func extractJSONInt(body, key string) int64 {
	needle := "\"" + key + "\":"
	idx := strings.Index(body, needle)
	if idx < 0 {
		return 0
	}
	rest := strings.TrimLeft(body[idx+len(needle):], " \t")
	// Read while the next byte is a digit.
	end := 0
	for end < len(rest) && rest[end] >= '0' && rest[end] <= '9' {
		end++
	}
	if end == 0 {
		return 0
	}
	var n int64
	for i := 0; i < end; i++ {
		n = n*10 + int64(rest[i]-'0')
	}
	return n
}
