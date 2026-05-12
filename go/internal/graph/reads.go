package graph

import (
	"fmt"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// Read helpers backing the Java side's QueryService / StatsService /
// GraphController. All return projections through rowsToNodes (defined in
// indexes.go) — `id`, `kind`, `label`, and optionally `file_path` / `layer`.
//
// Kuzu 0.7.1 caveats relevant here:
//   - LIMIT/SKIP values must be inlined literals, not bound parameters.
//   - count(*) on rels works fine across all rel tables via
//     `MATCH ()-[r]->()` — Kuzu treats the wildcard as the union of every
//     declared rel type.

// Count returns the total number of CodeNode rows.
func (s *Store) Count() (int64, error) {
	rows, err := s.Cypher("MATCH (n:CodeNode) RETURN count(n) AS c")
	if err != nil {
		return 0, fmt.Errorf("graph: count nodes: %w", err)
	}
	if len(rows) == 0 {
		return 0, nil
	}
	return asInt64(rows[0]["c"]), nil
}

// CountEdges returns the total number of edges across every rel table.
// The anonymous-rel pattern `()-[r]->()` unions all declared rel types in
// Kuzu — confirmed against the v0.7.1 binder.
func (s *Store) CountEdges() (int64, error) {
	rows, err := s.Cypher("MATCH ()-[r]->() RETURN count(r) AS c")
	if err != nil {
		return 0, fmt.Errorf("graph: count edges: %w", err)
	}
	if len(rows) == 0 {
		return 0, nil
	}
	return asInt64(rows[0]["c"]), nil
}

// CountNodesByKind returns {kind: count} across all 34 NodeKinds. Mirrors
// StatsService.getKindCounts() on the Java side.
func (s *Store) CountNodesByKind() (map[string]int64, error) {
	rows, err := s.Cypher(
		"MATCH (n:CodeNode) RETURN n.kind AS kind, count(n) AS cnt")
	if err != nil {
		return nil, fmt.Errorf("graph: count by kind: %w", err)
	}
	out := make(map[string]int64, len(rows))
	for _, r := range rows {
		k, _ := r["kind"].(string)
		out[k] = asInt64(r["cnt"])
	}
	return out, nil
}

// CountNodesByLayer returns {layer: count} across LayerClassifier output.
// Mirrors StatsService.getLayerCounts() on the Java side.
func (s *Store) CountNodesByLayer() (map[string]int64, error) {
	rows, err := s.Cypher(
		"MATCH (n:CodeNode) RETURN n.layer AS layer, count(n) AS cnt")
	if err != nil {
		return nil, fmt.Errorf("graph: count by layer: %w", err)
	}
	out := make(map[string]int64, len(rows))
	for _, r := range rows {
		l, _ := r["layer"].(string)
		out[l] = asInt64(r["cnt"])
	}
	return out, nil
}

// FindByID returns the single node with primary key id, or (nil, nil) when
// no such node exists. Mirrors GraphRepository.findById on the Java side.
func (s *Store) FindByID(id string) (*model.CodeNode, error) {
	rows, err := s.Cypher(`
		MATCH (n:CodeNode) WHERE n.id = $id
		RETURN n.id AS id, n.kind AS kind, n.label AS label,
		       n.file_path AS file_path, n.layer AS layer
		LIMIT 1`,
		map[string]any{"id": id})
	if err != nil {
		return nil, fmt.Errorf("graph: find by id: %w", err)
	}
	if len(rows) == 0 {
		return nil, nil
	}
	out := rowsToNodes(rows)
	if len(out) == 0 {
		return nil, nil
	}
	return out[0], nil
}

// FindByKindPaginated returns nodes of the given kind ordered by id with
// SKIP/LIMIT semantics. Mirrors GraphController's /api/kinds/{kind}.
// offset / limit must be non-negative; negative input is coerced to 0.
func (s *Store) FindByKindPaginated(kind string, offset, limit int) ([]*model.CodeNode, error) {
	if offset < 0 {
		offset = 0
	}
	if limit < 0 {
		limit = 0
	}
	// Kuzu 0.7.1 disallows parameter binding on SKIP/LIMIT — inline them.
	rows, err := s.Cypher(fmt.Sprintf(`
		MATCH (n:CodeNode) WHERE n.kind = $k
		RETURN n.id AS id, n.kind AS kind, n.label AS label,
		       n.file_path AS file_path, n.layer AS layer
		ORDER BY n.id SKIP %d LIMIT %d`, offset, limit),
		map[string]any{"k": kind})
	if err != nil {
		return nil, fmt.Errorf("graph: find by kind: %w", err)
	}
	return rowsToNodes(rows), nil
}

// FindIncomingNeighbors returns distinct nodes a where a -[*]-> n.id.
// Mirrors GraphController's /api/nodes/{id}/neighbors (incoming side).
// Note: Kuzu 0.7.1's binder drops the rel-pattern scope after `RETURN
// DISTINCT`, so the ORDER BY must reference the alias (`id`), not
// `a.id` — the SQL-standard DISTINCT scope behaviour.
func (s *Store) FindIncomingNeighbors(id string) ([]*model.CodeNode, error) {
	rows, err := s.Cypher(`
		MATCH (a:CodeNode)-[r]->(b:CodeNode) WHERE b.id = $id
		RETURN DISTINCT a.id AS id, a.kind AS kind, a.label AS label,
		       a.file_path AS file_path, a.layer AS layer
		ORDER BY id`,
		map[string]any{"id": id})
	if err != nil {
		return nil, fmt.Errorf("graph: incoming neighbors: %w", err)
	}
	return rowsToNodes(rows), nil
}

// FindOutgoingNeighbors returns distinct nodes b where n.id -[*]-> b.
// Mirrors GraphController's /api/nodes/{id}/neighbors (outgoing side).
// Same DISTINCT-scope caveat as FindIncomingNeighbors.
func (s *Store) FindOutgoingNeighbors(id string) ([]*model.CodeNode, error) {
	rows, err := s.Cypher(`
		MATCH (a:CodeNode)-[r]->(b:CodeNode) WHERE a.id = $id
		RETURN DISTINCT b.id AS id, b.kind AS kind, b.label AS label,
		       b.file_path AS file_path, b.layer AS layer
		ORDER BY id`,
		map[string]any{"id": id})
	if err != nil {
		return nil, fmt.Errorf("graph: outgoing neighbors: %w", err)
	}
	return rowsToNodes(rows), nil
}

// asInt64 coerces Kuzu's count(*) cell to int64. Kuzu returns counts as
// int64 today; the helper guards against the type drifting to int32 / int
// across versions.
func asInt64(v any) int64 {
	switch x := v.(type) {
	case int64:
		return x
	case int32:
		return int64(x)
	case int:
		return int64(x)
	case uint64:
		return int64(x)
	case float64:
		return int64(x)
	default:
		return 0
	}
}
