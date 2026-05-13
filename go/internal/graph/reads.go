package graph

import (
	"encoding/json"
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

// LoadAllNodes pulls every CodeNode row out of Kuzu in deterministic ID
// order and hydrates the columns + the JSON `props` blob back into
// model.CodeNode. Used by the stats command, which currently re-uses the
// in-memory StatsService.ComputeStats path rather than per-category Cypher
// aggregations. On large graphs this is materially heavier than the Java
// side's TopologyService refactor — see the gotcha in CLAUDE.md for the
// follow-up plan. Empty graph returns (nil, nil).
func (s *Store) LoadAllNodes() ([]*model.CodeNode, error) {
	rows, err := s.Cypher(`
		MATCH (n:CodeNode)
		RETURN n.id AS id, n.kind AS kind, n.label AS label, n.fqn AS fqn,
		       n.file_path AS file_path, n.line_start AS line_start,
		       n.line_end AS line_end, n.module AS module, n.layer AS layer,
		       n.language AS language, n.framework AS framework,
		       n.confidence AS confidence, n.source AS source,
		       n.props AS props
		ORDER BY n.id`)
	if err != nil {
		return nil, fmt.Errorf("graph: load all nodes: %w", err)
	}
	out := make([]*model.CodeNode, 0, len(rows))
	for _, r := range rows {
		n := &model.CodeNode{}
		if v, ok := r["id"].(string); ok {
			n.ID = v
		}
		if v, ok := r["kind"].(string); ok {
			if k, err := model.ParseNodeKind(v); err == nil {
				n.Kind = k
			}
		}
		if v, ok := r["label"].(string); ok {
			n.Label = v
		}
		if v, ok := r["fqn"].(string); ok {
			n.FQN = v
		}
		if v, ok := r["file_path"].(string); ok {
			n.FilePath = v
		}
		n.LineStart = int(asInt64(r["line_start"]))
		n.LineEnd = int(asInt64(r["line_end"]))
		if v, ok := r["module"].(string); ok {
			n.Module = v
		}
		if v, ok := r["layer"].(string); ok {
			if l, err := model.ParseLayer(v); err == nil {
				n.Layer = l
			}
		}
		if v, ok := r["confidence"].(string); ok {
			if c, err := model.ParseConfidence(v); err == nil {
				n.Confidence = c
			}
		}
		if v, ok := r["source"].(string); ok {
			n.Source = v
		}
		// Hydrate JSON-encoded properties. The bulk loader writes an empty
		// `{}` for nil maps so a parse failure here is a real corruption,
		// not a missing field — but we tolerate the failure and fall back
		// to nil to keep the stats path lossy-tolerant rather than fatal.
		n.Properties = map[string]any{}
		if propsStr, ok := r["props"].(string); ok && propsStr != "" {
			_ = json.Unmarshal([]byte(propsStr), &n.Properties)
		}
		// The first-class language / framework columns mirror what the bulk
		// loader pulled out of Properties — re-stamp them so StatsService
		// path that reads Properties sees the same view.
		if v, ok := r["language"].(string); ok && v != "" {
			n.Properties["language"] = v
		}
		if v, ok := r["framework"].(string); ok && v != "" {
			n.Properties["framework"] = v
		}
		out = append(out, n)
	}
	return out, nil
}

// LoadAllEdges pulls every edge from every rel table, hydrating model.CodeEdge.
// Determinism: rows come out grouped by EdgeKind in declaration order, then
// sorted by edge id within each kind. Empty graph returns (nil, nil).
func (s *Store) LoadAllEdges() ([]*model.CodeEdge, error) {
	var out []*model.CodeEdge
	for _, kind := range model.AllEdgeKinds() {
		tbl := relTableName(kind)
		rows, err := s.Cypher(fmt.Sprintf(`
			MATCH (a:CodeNode)-[r:%s]->(b:CodeNode)
			RETURN r.id AS id, r.confidence AS confidence,
			       r.source AS source, r.props AS props,
			       a.id AS source_id, b.id AS target_id
			ORDER BY r.id`, tbl))
		if err != nil {
			return nil, fmt.Errorf("graph: load edges %s: %w", tbl, err)
		}
		for _, r := range rows {
			e := &model.CodeEdge{Kind: kind}
			if v, ok := r["id"].(string); ok {
				e.ID = v
			}
			if v, ok := r["source_id"].(string); ok {
				e.SourceID = v
			}
			if v, ok := r["target_id"].(string); ok {
				e.TargetID = v
			}
			if v, ok := r["confidence"].(string); ok {
				if c, err := model.ParseConfidence(v); err == nil {
					e.Confidence = c
				}
			}
			if v, ok := r["source"].(string); ok {
				e.Source = v
			}
			e.Properties = map[string]any{}
			if propsStr, ok := r["props"].(string); ok && propsStr != "" {
				_ = json.Unmarshal([]byte(propsStr), &e.Properties)
			}
			out = append(out, e)
		}
	}
	return out, nil
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
