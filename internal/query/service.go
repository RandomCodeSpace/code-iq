package query

import (
	"fmt"
	"strings"

	"github.com/randomcodespace/codeiq/internal/graph"
	"github.com/randomcodespace/codeiq/internal/model"
)

// Service is the high-level read service wrapping a graph.Store. Mirrors
// QueryService.java — consumers / producers / callers / dependencies /
// dependents / shortest-path / cycles / dead-code. The Java side uses
// Neo4j's single RELATES_TO edge wrapper with a `kind` property; on Kuzu we
// have one rel table per EdgeKind, so the queries below filter by
// `LABEL(r)` rather than `r.kind`.
//
// Kuzu 0.7.1 feature gaps relevant here:
//   - Shortest path uses Kuzu's `[* SHORTEST n..m]` syntax, NOT Neo4j's
//     `shortestPath((a)-[*..20]-(b))` function.
//   - Cycles use the recursive pattern `(n)-[*2..N]->(n)`; Kuzu requires an
//     explicit upper bound (default 30 if omitted).
//   - There is no `TYPE(r)` — use `LABEL(r)` to get the rel table name.
type Service struct {
	store *graph.Store
}

// NewService constructs a Service bound to the given graph.Store.
func NewService(store *graph.Store) *Service { return &Service{store: store} }

// FindConsumers returns nodes m where m -[consumes|listens]-> target.
// Mirrors QueryService.findConsumers + GraphStore.findConsumers on the Java
// side; the runtime-edge set is the consumer-direction subset.
func (s *Service) FindConsumers(id string) ([]*model.CodeNode, error) {
	return s.incomingByKinds(id, []string{"CONSUMES", "LISTENS"})
}

// FindProducers returns nodes m where m -[produces|publishes]-> target.
// Mirrors QueryService.findProducers.
func (s *Service) FindProducers(id string) ([]*model.CodeNode, error) {
	return s.incomingByKinds(id, []string{"PRODUCES", "PUBLISHES"})
}

// FindCallers returns nodes m where m -[calls]-> target. Mirrors
// QueryService.findCallers.
func (s *Service) FindCallers(id string) ([]*model.CodeNode, error) {
	return s.incomingByKinds(id, []string{"CALLS"})
}

// FindDependencies returns nodes m where source -[depends_on]-> m. Mirrors
// QueryService.findDependencies.
func (s *Service) FindDependencies(id string) ([]*model.CodeNode, error) {
	return s.outgoingByKinds(id, []string{"DEPENDS_ON"})
}

// FindDependents returns nodes m where m -[depends_on]-> source. Mirrors
// QueryService.findDependents.
func (s *Service) FindDependents(id string) ([]*model.CodeNode, error) {
	return s.outgoingDependents(id, []string{"DEPENDS_ON"})
}

// outgoingByKinds returns distinct nodes b where a -[r]-> b and a.id = id
// and LABEL(r) ∈ kinds. Kuzu's multi-label rel syntax is
// `[r:KIND1|:KIND2|...]` — but the leading colon ONLY appears on the first
// alternative in Kuzu 0.7. To keep the helper kind-list agnostic we build
// the pattern as `[r:K1|K2|...]` which Kuzu parses cleanly.
func (s *Service) outgoingByKinds(id string, kinds []string) ([]*model.CodeNode, error) {
	relPat := relAlternation(kinds)
	q := fmt.Sprintf(`
		MATCH (a:CodeNode)-[r%s]->(b:CodeNode) WHERE a.id = $id
		RETURN DISTINCT b.id AS id, b.kind AS kind, b.label AS label,
		       b.file_path AS file_path, b.layer AS layer
		ORDER BY id`, relPat)
	rows, err := s.store.Cypher(q, map[string]any{"id": id})
	if err != nil {
		return nil, fmt.Errorf("query: outgoing by kinds %v: %w", kinds, err)
	}
	return rowsToNodes(rows), nil
}

// incomingByKinds returns distinct nodes a where a -[r]-> b and b.id = id
// and LABEL(r) ∈ kinds.
func (s *Service) incomingByKinds(id string, kinds []string) ([]*model.CodeNode, error) {
	relPat := relAlternation(kinds)
	q := fmt.Sprintf(`
		MATCH (a:CodeNode)-[r%s]->(b:CodeNode) WHERE b.id = $id
		RETURN DISTINCT a.id AS id, a.kind AS kind, a.label AS label,
		       a.file_path AS file_path, a.layer AS layer
		ORDER BY id`, relPat)
	rows, err := s.store.Cypher(q, map[string]any{"id": id})
	if err != nil {
		return nil, fmt.Errorf("query: incoming by kinds %v: %w", kinds, err)
	}
	return rowsToNodes(rows), nil
}

// outgoingDependents is the dependent-direction analogue for DEPENDS_ON.
// Reads "everything that depends on this node": nodes m where m -[r]-> id
// — same shape as incomingByKinds but kept as a separate helper for
// readability so callers reading `FindDependents(B)` map to a clearly named
// helper rather than `incomingByKinds(...)`.
func (s *Service) outgoingDependents(id string, kinds []string) ([]*model.CodeNode, error) {
	return s.incomingByKinds(id, kinds)
}

// FindShortestPath returns a list of node IDs forming the shortest directed
// path from source to target, inclusive of both endpoints. Returns an empty
// slice when no path exists. Mirrors QueryService.shortestPath on the Java
// side (which uses Neo4j shortestPath() — see Kuzu syntax note above).
//
// Kuzu 0.7 requires:
//   - explicit upper bound on the recursive pattern
//   - rel pattern with named rel variable so nodes(p) can be extracted
//
// We use `[* SHORTEST 1..20]` to match the Java cap (`*..20`).
func (s *Service) FindShortestPath(source, target string) ([]string, error) {
	if source == target {
		return []string{source}, nil
	}
	// Kuzu 0.7 binder rejects `[n IN nodes(p) | n.id]` list-comprehension
	// (Variable n not in scope). Use the built-in `properties(nodes(p), 'id')`
	// helper which returns the same shape — verified against Kuzu 0.7 docs.
	rows, err := s.store.Cypher(`
		MATCH p = (a:CodeNode)-[* SHORTEST 1..20]->(b:CodeNode)
		WHERE a.id = $src AND b.id = $tgt
		RETURN properties(nodes(p), 'id') AS ids LIMIT 1`,
		map[string]any{"src": source, "tgt": target})
	if err != nil {
		return nil, fmt.Errorf("query: shortest path: %w", err)
	}
	if len(rows) == 0 {
		return []string{}, nil
	}
	return idsFromRow(rows[0]["ids"]), nil
}

// FindCycles returns up to `limit` cycles in the graph. Each cycle is a
// node-id slice where the first and last elements are equal. Mirrors
// QueryService.findCycles + GraphStore.findCycles.
//
// Implementation note: Kuzu's recursive pattern requires an upper bound
// (default 30 if omitted). We cap at 10 to match the Java side's hop
// budget — same trade between completeness and query time.
func (s *Service) FindCycles(limit int) ([][]string, error) {
	if limit <= 0 {
		limit = 100
	}
	// Same list-comprehension caveat as FindShortestPath —
	// `properties(nodes(p), 'id')` is the supported shape for projecting
	// recursive-rel paths.
	rows, err := s.store.Cypher(`
		MATCH p = (a:CodeNode)-[* 2..10]->(b:CodeNode)
		WHERE a.id = b.id
		RETURN properties(nodes(p), 'id') AS ids LIMIT $lim`,
		map[string]any{"lim": int64(limit)})
	if err != nil {
		return nil, fmt.Errorf("query: find cycles: %w", err)
	}
	cycles := make([][]string, 0, len(rows))
	for _, r := range rows {
		cycles = append(cycles, idsFromRow(r["ids"]))
	}
	return cycles, nil
}

// semanticEdgeKinds enumerates the edges that count as "usage" for
// dead-code detection. Structural edges (CONTAINS, DEFINES) are excluded
// because every node typically has one of those from its parent module.
var semanticEdgeKinds = []string{
	"CALLS", "IMPORTS", "DEPENDS_ON", "EXTENDS", "IMPLEMENTS",
	"INJECTS", "QUERIES", "MAPS_TO", "CONSUMES", "LISTENS",
	"INVOKES_RMI", "OVERRIDES", "CONNECTS_TO", "TRIGGERS",
	"RENDERS", "PROTECTS",
}

// entryPointKinds enumerates node kinds that are intended to have no
// incoming semantic edges — flagging them as dead would be a false positive.
// Mirrors QueryService.ENTRY_POINT_KINDS on the Java side.
var entryPointKinds = []string{
	"endpoint", "websocket_endpoint", "migration", "config_file",
	"config_key", "config_definition", "guard", "middleware",
	"topic", "queue", "event", "message_queue",
}

// defaultDeadCodeKinds is the node-kind filter used when callers pass an
// empty kinds list. Mirrors QueryService.findDeadCode default behaviour.
var defaultDeadCodeKinds = []string{
	"class", "method", "interface", "abstract_class", "component", "service",
}

// FindDeadCode returns nodes of the given kinds that have no incoming
// semantic edge and are not on the entry-point list. Mirrors
// QueryService.findDeadCode + GraphStore.findNodesWithoutIncomingSemantic.
//
// Kuzu 0.7 cap: `NOT EXISTS { MATCH ... }` works (verified against docs).
// The semantic-edge filter is an `LABEL(r) IN [...]` predicate, not a
// rel-pattern alternation, so the existence check stays a single MATCH.
func (s *Service) FindDeadCode(kinds []string, limit int) ([]*model.CodeNode, error) {
	if len(kinds) == 0 {
		kinds = defaultDeadCodeKinds
	}
	if limit <= 0 {
		limit = 100
	}

	// Kuzu binder gap (still present in 0.11): parameters declared at the
	// outer scope are not visible inside an `EXISTS { MATCH ... WHERE ... }`
	// subquery, so a `LABEL(r) IN $semanticKinds` predicate inside EXISTS
	// fails with "Parameter semanticKinds not found". Workaround: inline the
	// semantic edges as a rel-pattern alternation, which is bound at parse
	// time. Outer-scope $kinds / $excludeKinds work fine because they live
	// in the top-level WHERE clause.
	semanticPat := ":" + strings.Join(semanticEdgeKinds, "|")
	q := fmt.Sprintf(`
		MATCH (n:CodeNode)
		WHERE n.kind IN $kinds
		  AND NOT n.kind IN $excludeKinds
		  AND NOT EXISTS {
		      MATCH (m:CodeNode)-[r%s]->(n)
		  }
		RETURN n.id AS id, n.kind AS kind, n.label AS label,
		       n.file_path AS file_path, n.layer AS layer
		ORDER BY n.id LIMIT $lim`, semanticPat)

	rows, err := s.store.Cypher(q, map[string]any{
		"kinds":        kinds,
		"excludeKinds": entryPointKinds,
		"lim":          int64(limit),
	})
	if err != nil {
		return nil, fmt.Errorf("query: find dead code: %w", err)
	}
	return rowsToNodes(rows), nil
}

// rowsToNodes mirrors graph.rowsToNodes — kept package-local here to avoid
// exporting the helper. Projects the canonical {id,kind,label,file_path,
// layer} columns onto CodeNode shells.
func rowsToNodes(rows []map[string]any) []*model.CodeNode {
	out := make([]*model.CodeNode, 0, len(rows))
	for _, r := range rows {
		n := &model.CodeNode{}
		if id, ok := r["id"].(string); ok {
			n.ID = id
		}
		if kindStr, ok := r["kind"].(string); ok {
			if k, err := model.ParseNodeKind(kindStr); err == nil {
				n.Kind = k
			}
		}
		if label, ok := r["label"].(string); ok {
			n.Label = label
		}
		if fp, ok := r["file_path"].(string); ok {
			n.FilePath = fp
		}
		if layer, ok := r["layer"].(string); ok {
			if l, err := model.ParseLayer(layer); err == nil {
				n.Layer = l
			}
		}
		out = append(out, n)
	}
	return out
}

// idsFromRow extracts a []string from a Kuzu list value. Kuzu lists round
// trip as []any (or []string after the kuzuValueToGoValue projection); we
// accept either.
func idsFromRow(v any) []string {
	switch x := v.(type) {
	case []string:
		return x
	case []any:
		out := make([]string, 0, len(x))
		for _, item := range x {
			if s, ok := item.(string); ok {
				out = append(out, s)
			}
		}
		return out
	default:
		return nil
	}
}

// relAlternation builds Kuzu's rel alternation pattern for a list of rel
// kinds. Empty returns "" (anonymous rel pattern, matches any kind).
//
//	[]                       → ""        — matches anything
//	["CALLS"]                → ":CALLS"
//	["CALLS","DEPENDS_ON"]   → ":CALLS|DEPENDS_ON"
//
// Kuzu 0.7 accepts both `:K1|:K2` and `:K1|K2`; we use the shorter form to
// keep query text compact in logs.
func relAlternation(kinds []string) string {
	if len(kinds) == 0 {
		return ""
	}
	return ":" + strings.Join(kinds, "|")
}
