package graph

import (
	"fmt"
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// CreateIndexes installs the fulltext-search indexes the read side relies
// on. Mirrors GraphStore.createIndexes() on the Java side, which declares
// two Neo4j fulltext indexes:
//
//   - search_index: covers label_lower + fqn_lower. Powers /api/search and
//     the search_graph MCP tool.
//   - lexical_index: covers prop_lex_comment + prop_lex_config_keys.
//     Powers LexicalQueryService's doc-comment / config-key search.
//
// Implementation note (Kuzu version gap): Kuzu's official FTS extension
// ships pre-bundled from v0.11.3 onwards. We pin go-kuzu v0.7.1 (Kuzu
// 0.7.x runtime), which requires a network INSTALL of the FTS extension —
// incompatible with the air-gapped build policy. We therefore expose the
// same SearchByLabel / SearchLexical surface and back it with Cypher
// CONTAINS predicates. When we bump Kuzu past 0.11.3 the implementation
// swaps to CALL CREATE_FTS_INDEX / QUERY_FTS_INDEX without touching the
// caller surface.
//
// Because there is no actual index to create at this version, CreateIndexes
// is a no-op that returns nil. It stays in the API so call sites in the
// enrich command line up with the eventual FTS implementation.
func (s *Store) CreateIndexes() error {
	// Touch the property columns to make sure schema is in place. We do
	// NOT attempt INSTALL fts here — that path requires network access
	// the air-gapped build policy forbids (see playbooks/build.md).
	return nil
}

// SearchByLabel runs a case-insensitive substring search across
// label_lower and fqn_lower. Returns up to `limit` nodes ordered by id for
// stable test output. Behaviour matches the Java search_index contract at
// the API surface; ranking differs (no BM25 until Kuzu FTS lands).
func (s *Store) SearchByLabel(q string, limit int) ([]*model.CodeNode, error) {
	needle := strings.ToLower(q)
	// Kuzu 0.7.1 rejects parameter binding on LIMIT — the value must be
	// an inline literal. Coerce `limit` to a non-negative int and inline
	// it via fmt; the user-supplied needle still goes through prepared
	// parameter binding.
	if limit < 0 {
		limit = 0
	}
	rows, err := s.Cypher(fmt.Sprintf(`
		MATCH (n:CodeNode)
		WHERE n.label_lower CONTAINS $q OR n.fqn_lower CONTAINS $q
		RETURN n.id AS id, n.kind AS kind, n.label AS label,
		       n.file_path AS file_path, n.layer AS layer
		ORDER BY n.id LIMIT %d`, limit),
		map[string]any{"q": needle})
	if err != nil {
		return nil, fmt.Errorf("graph: search by label: %w", err)
	}
	return rowsToNodes(rows), nil
}

// SearchLexical runs a case-insensitive substring search across
// prop_lex_comment and prop_lex_config_keys — the two columns
// LexicalEnricher fills with doc-comment text and surfaced config keys.
// Same Kuzu version caveat as SearchByLabel above.
func (s *Store) SearchLexical(q string, limit int) ([]*model.CodeNode, error) {
	needle := strings.ToLower(q)
	if limit < 0 {
		limit = 0
	}
	// Kuzu 0.7.1 uses SQL-style `lower()`, not `toLower()`.
	rows, err := s.Cypher(fmt.Sprintf(`
		MATCH (n:CodeNode)
		WHERE lower(n.prop_lex_comment) CONTAINS $q
		   OR lower(n.prop_lex_config_keys) CONTAINS $q
		RETURN n.id AS id, n.kind AS kind, n.label AS label,
		       n.file_path AS file_path, n.layer AS layer
		ORDER BY n.id LIMIT %d`, limit),
		map[string]any{"q": needle})
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
