package graph

import (
	"fmt"
	"strings"

	"github.com/randomcodespace/codeiq/internal/model"
)

// ApplySchema creates the single CodeNode node table plus one REL table per
// EdgeKind. Idempotent — repeated calls are no-ops via `IF NOT EXISTS`.
// Mirrors the implicit label-driven schema Spring Data Neo4j gives the Java
// side; on Kuzu the schema is explicit.
//
// CodeNode is one table backing all 34 NodeKinds — `kind` is a column, not
// a label. Properties round-trip through a JSON-serialised `props` column
// plus a small set of first-class columns we want to index / project on.
func (s *Store) ApplySchema() error {
	// GraphMeta stores small key→value strings (e.g., manifest hash for the
	// incremental enrich short-circuit). One row per key; PK enforces uniqueness.
	metaDDL := `CREATE NODE TABLE IF NOT EXISTS GraphMeta(
		meta_key STRING,
		value STRING,
		PRIMARY KEY(meta_key))`
	if _, err := s.Cypher(metaDDL); err != nil {
		return fmt.Errorf("graph: create GraphMeta: %w", err)
	}

	nodeDDL := `CREATE NODE TABLE IF NOT EXISTS CodeNode(
		id STRING,
		kind STRING,
		label STRING,
		fqn STRING,
		file_path STRING,
		line_start INT64,
		line_end INT64,
		module STRING,
		layer STRING,
		language STRING,
		framework STRING,
		confidence STRING,
		source STRING,
		label_lower STRING,
		fqn_lower STRING,
		prop_lex_comment STRING,
		prop_lex_config_keys STRING,
		props STRING,
		PRIMARY KEY(id))`
	if _, err := s.Cypher(nodeDDL); err != nil {
		return fmt.Errorf("graph: create CodeNode: %w", err)
	}

	// One REL table per EdgeKind. `props` holds the JSON-serialised property
	// map; first-class `id`, `confidence`, and `source` columns mirror what
	// every detector emits.
	for _, ek := range model.AllEdgeKinds() {
		ddl := fmt.Sprintf(`CREATE REL TABLE IF NOT EXISTS %s(
			FROM CodeNode TO CodeNode,
			id STRING,
			confidence STRING,
			source STRING,
			props STRING)`, relTableName(ek))
		if _, err := s.Cypher(ddl); err != nil {
			return fmt.Errorf("graph: create rel %s: %w", ek, err)
		}
	}
	return nil
}

// relTableName converts an EdgeKind ("calls" -> "CALLS"). Kuzu rel-table
// names are uppercase by convention so the Cypher `:KIND` notation lines up
// with the table name directly.
func relTableName(ek model.EdgeKind) string {
	return strings.ToUpper(ek.String())
}
