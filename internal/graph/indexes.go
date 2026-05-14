package graph

import (
	"fmt"
	"strings"

	"github.com/randomcodespace/codeiq/internal/model"
)

// FTS index names. CreateIndexes builds these after enrich. Read paths
// query them via QUERY_FTS_INDEX.
const (
	ftsLabelIndex   = "code_node_label_fts"
	ftsLexicalIndex = "code_node_lexical_fts"
)

// CreateIndexes installs the fulltext-search indexes the read side relies
// on. Two indexes are created:
//
//   - code_node_label_fts: covers label + fqn_lower. Powers SearchByLabel
//     and the search_graph MCP tool surface.
//   - code_node_lexical_fts: covers prop_lex_comment + prop_lex_config_keys.
//     Powers LexicalQueryService's doc-comment / config-key search.
//
// Idempotent: existing indexes are dropped before re-create. The enrich
// pipeline calls this once after BulkLoadNodes / BulkLoadEdges complete,
// so the indexes always reflect the latest snapshot.
//
// FTS bundled in Kuzu 0.11.3+ (no network install needed — air-gapped safe).
func (s *Store) CreateIndexes() error {
	// FTS extension ships bundled but still needs LOAD to register the
	// catalog functions. INSTALL is a no-op when bundled.
	if _, err := s.Cypher("INSTALL fts;"); err != nil {
		return fmt.Errorf("graph: install fts: %w", err)
	}
	if _, err := s.Cypher("LOAD EXTENSION fts;"); err != nil {
		return fmt.Errorf("graph: load fts: %w", err)
	}
	// Drop-then-create — idempotent across re-enrich. Dropping a missing
	// index errors; ignore that single error path.
	for _, idx := range []string{ftsLabelIndex, ftsLexicalIndex} {
		_, _ = s.Cypher(fmt.Sprintf("CALL DROP_FTS_INDEX('CodeNode', '%s');", idx))
	}
	if _, err := s.Cypher(fmt.Sprintf(
		`CALL CREATE_FTS_INDEX('CodeNode', '%s', ['label', 'fqn_lower']);`,
		ftsLabelIndex)); err != nil {
		return fmt.Errorf("graph: create fts label index: %w", err)
	}
	if _, err := s.Cypher(fmt.Sprintf(
		`CALL CREATE_FTS_INDEX('CodeNode', '%s', ['prop_lex_comment', 'prop_lex_config_keys']);`,
		ftsLexicalIndex)); err != nil {
		return fmt.Errorf("graph: create fts lexical index: %w", err)
	}
	return nil
}

// SearchByLabel runs a fulltext search across the label + fqn_lower index.
// The query is auto-suffixed with '*' to give prefix matching (so 'auth'
// matches 'AuthService' identifiers). Results are ranked by BM25 score.
// Falls back to CONTAINS predicate when the FTS index hasn't been built
// (pre-enrich or enrich aborted before CreateIndexes).
func (s *Store) SearchByLabel(q string, limit int) ([]*model.CodeNode, error) {
	return s.ftsSearch(ftsLabelIndex, q, limit, s.searchByLabelFallback)
}

// SearchLexical runs a fulltext search across the prose columns
// (prop_lex_comment + prop_lex_config_keys). BM25 ranks results. Same
// CONTAINS fallback as SearchByLabel for pre-enrich graphs.
func (s *Store) SearchLexical(q string, limit int) ([]*model.CodeNode, error) {
	return s.ftsSearch(ftsLexicalIndex, q, limit, s.searchLexicalFallback)
}

// ftsSearch is the shared FTS path for SearchByLabel and SearchLexical.
// On any FTS error (missing index, malformed query, etc.) it routes to the
// caller-supplied CONTAINS fallback.
func (s *Store) ftsSearch(idx, q string, limit int,
	fallback func(string, int) ([]*model.CodeNode, error)) ([]*model.CodeNode, error) {
	if limit < 0 {
		limit = 0
	}
	needle := strings.TrimSpace(strings.ToLower(q))
	// Prefix-search via wildcard: "auth" → "auth*". Skip if user already
	// supplied a wildcard or a multi-token query (FTS treats space as AND).
	if needle != "" && !strings.ContainsAny(needle, "* ") {
		needle += "*"
	}
	rows, err := s.Cypher(`
		CALL QUERY_FTS_INDEX('CodeNode', $idx, $q)
		WITH node AS n, score
		RETURN n.id AS id, n.kind AS kind, n.label AS label,
		       n.file_path AS file_path, n.layer AS layer, score
		ORDER BY score DESC, n.id
		LIMIT $lim`,
		map[string]any{"idx": idx, "q": needle, "lim": int64(limit)})
	if err != nil {
		return fallback(needle, limit)
	}
	return rowsToNodes(rows), nil
}

// searchByLabelFallback uses CONTAINS — same shape as pre-FTS code, retained
// for graphs where CreateIndexes has not run. Strips the trailing '*' added
// by ftsSearch since CONTAINS is already substring-y.
func (s *Store) searchByLabelFallback(needle string, limit int) ([]*model.CodeNode, error) {
	q := strings.TrimSuffix(needle, "*")
	rows, err := s.Cypher(`
		MATCH (n:CodeNode)
		WHERE n.label_lower CONTAINS $q OR n.fqn_lower CONTAINS $q
		RETURN n.id AS id, n.kind AS kind, n.label AS label,
		       n.file_path AS file_path, n.layer AS layer
		ORDER BY n.id LIMIT $lim`,
		map[string]any{"q": q, "lim": int64(limit)})
	if err != nil {
		return nil, fmt.Errorf("graph: search by label: %w", err)
	}
	return rowsToNodes(rows), nil
}

// searchLexicalFallback uses CONTAINS with toLower() over prose columns.
// Retained for graphs that haven't run enrich/CreateIndexes.
func (s *Store) searchLexicalFallback(needle string, limit int) ([]*model.CodeNode, error) {
	q := strings.TrimSuffix(needle, "*")
	rows, err := s.Cypher(`
		MATCH (n:CodeNode)
		WHERE toLower(n.prop_lex_comment) CONTAINS $q
		   OR toLower(n.prop_lex_config_keys) CONTAINS $q
		RETURN n.id AS id, n.kind AS kind, n.label AS label,
		       n.file_path AS file_path, n.layer AS layer
		ORDER BY n.id LIMIT $lim`,
		map[string]any{"q": q, "lim": int64(limit)})
	if err != nil {
		return nil, fmt.Errorf("graph: search lexical: %w", err)
	}
	return rowsToNodes(rows), nil
}

// rowsToNodes projects the canonical {id, kind, label, file_path, layer}
// columns onto CodeNode shells. Used by the search helpers here and the
// per-kind / neighbour read helpers in reads.go.
//
// Optional projections are tolerant — a caller's RETURN clause that omits
// file_path or layer just leaves those fields zero-valued.
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
