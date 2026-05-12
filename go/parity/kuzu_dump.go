// Package parity contains the cross-binary diff harness. Phase 1 dumps the
// SQLite cache to a normalized JSON form; phase 2 extends to the Kuzu graph
// produced by `codeiq enrich`. DumpKuzu lives here so the harness can compare
// post-enrich graphs node-for-node and edge-for-edge against the Java side's
// Neo4j dump.
package parity

import (
	"encoding/json"
	"fmt"
	"sort"

	"github.com/randomcodespace/codeiq/go/internal/graph"
)

// DumpKuzu returns a deterministic JSON dump of all nodes and edges in the
// Kuzu store at `dir`. The shape mirrors what java-normalize.jq produces from
// the Java side's `codeiq graph -f json` output, so the parity harness can
// diff the two byte-for-byte modulo the entries listed in
// expected-divergence.json.
//
// Kuzu-specific notes:
//   - The store at `dir` must have been written by `codeiq enrich` (schema +
//     bulk-loaded nodes + per-EdgeKind rel tables + indexes).
//   - The rel-type accessor is `label(r)` in Kuzu 0.7.1 — the Cypher standard
//     `type(r)` is not bound. The "edges" entries carry the rel-table name as
//     the `kind` field so the JSON looks like the Java/Neo4j side.
//   - LIMIT cannot be parameter-bound in Kuzu 0.7.1; we don't need LIMIT here
//     because the diff requires the full set.
//   - Cypher ORDER BY drops the rel-pattern scope after RETURN, so we sort
//     defensively in Go on top of any server-side ordering.
func DumpKuzu(dir string) ([]byte, error) {
	s, err := graph.Open(dir)
	if err != nil {
		return nil, fmt.Errorf("parity: open kuzu: %w", err)
	}
	defer s.Close()

	nodes, err := s.Cypher(`
		MATCH (n:CodeNode)
		RETURN n.id AS id, n.kind AS kind, n.label AS label, n.fqn AS fqn,
		       n.file_path AS file_path, n.layer AS layer,
		       n.framework AS framework, n.language AS language,
		       n.prop_lex_comment AS lex_comment,
		       n.prop_lex_config_keys AS lex_config_keys
		ORDER BY n.id`)
	if err != nil {
		return nil, fmt.Errorf("parity: dump nodes: %w", err)
	}
	edges, err := s.Cypher(`
		MATCH (a:CodeNode)-[r]->(b:CodeNode)
		RETURN r.id AS id, label(r) AS kind, a.id AS source, b.id AS target
		ORDER BY r.id`)
	if err != nil {
		return nil, fmt.Errorf("parity: dump edges: %w", err)
	}

	// Defensive Go-side sort. Cypher ORDER BY is stable in Kuzu 0.7.1 today,
	// but the binder treats the order-key alias loosely after DISTINCT /
	// aggregation — sorting here pins the result regardless of upstream drift.
	sortByID(nodes)
	sortByID(edges)

	// Coerce nil slices to empty slices so the JSON output is always `[]`
	// rather than `null` — keeps the byte-level diff stable across stores
	// that happen to be empty.
	if nodes == nil {
		nodes = []map[string]any{}
	}
	if edges == nil {
		edges = []map[string]any{}
	}

	return json.MarshalIndent(map[string]any{
		"nodes": nodes,
		"edges": edges,
	}, "", "  ")
}

// sortByID sorts a result set by the "id" column. Rows missing an id
// (shouldn't happen post-enrich, but defensive against future schema drift)
// stably sort to the front.
func sortByID(rows []map[string]any) {
	sort.SliceStable(rows, func(i, j int) bool {
		return idOf(rows[i]) < idOf(rows[j])
	})
}

// idOf returns the row's "id" column as a string, or "" when absent / not
// a string. Defensive against Cypher rows where a missing column projects to
// nil — the JSON output then carries `"id": null` rather than "".
func idOf(row map[string]any) string {
	if v, ok := row["id"]; ok {
		if s, ok := v.(string); ok {
			return s
		}
	}
	return ""
}
