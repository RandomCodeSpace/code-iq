package graph

import (
	"fmt"
	"time"

	kuzu "github.com/kuzudb/go-kuzu"
)

// DefaultQueryTimeout matches the Java side's DBMS-level cap
// (GraphDatabaseSettings.transaction_timeout = 30s in Neo4jConfig).
// Kuzu accepts the timeout in milliseconds on the Connection.
const DefaultQueryTimeout = 30 * time.Second

// Cypher runs a Cypher statement and returns rows as []map[string]any. For
// DDL or void queries the returned slice may be empty (or contain whatever
// status row Kuzu emits). If args is supplied the query is prepared and
// bound; otherwise it is executed directly.
//
// The caller-supplied map is read-only — parameter values are copied through
// go-kuzu's Execute path.
func (s *Store) Cypher(query string, args ...map[string]any) ([]map[string]any, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.conn == nil {
		return nil, fmt.Errorf("graph: store closed")
	}
	if s.readOnly {
		if kw := MutationKeyword(query); kw != "" {
			return nil, fmt.Errorf("graph: write query rejected on read-only store (blocked keyword: %s)", kw)
		}
	}
	var params map[string]any
	if len(args) > 0 {
		params = args[0]
	}
	qr, err := execQuery(s.conn, query, params)
	if err != nil {
		return nil, fmt.Errorf("graph: cypher: %w", err)
	}
	defer qr.Close()
	return decodeResult(qr)
}

// CypherRows runs query, materialises up to maxRows result rows, and
// reports whether the query produced more rows than the cap. Used by
// the run_cypher MCP tool which needs to surface a `truncated` flag
// without inlining `LIMIT N` into the user-supplied query string (the
// query may already have its own LIMIT — see the McpTools row-cap
// gotcha in CLAUDE.md).
//
// The mutation gate from Cypher() applies here too: on a read-only
// store, any blocked-keyword query short-circuits with an error.
func (s *Store) CypherRows(query string, args map[string]any, maxRows int) ([]map[string]any, bool, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.conn == nil {
		return nil, false, fmt.Errorf("graph: store closed")
	}
	if s.readOnly {
		if kw := MutationKeyword(query); kw != "" {
			return nil, false, fmt.Errorf("graph: write query rejected on read-only store (blocked keyword: %s)", kw)
		}
	}
	qr, err := execQuery(s.conn, query, args)
	if err != nil {
		return nil, false, fmt.Errorf("graph: cypher: %w", err)
	}
	defer qr.Close()
	if maxRows <= 0 {
		maxRows = 1
	}
	rows := make([]map[string]any, 0, maxRows)
	truncated := false
	for qr.HasNext() {
		if len(rows) >= maxRows {
			// Drain one more tuple to confirm there *are* more rows; we don't
			// keep the value, just the truncated flag.
			truncated = true
			t, err := qr.Next()
			if err == nil {
				t.Close()
			}
			break
		}
		tuple, err := qr.Next()
		if err != nil {
			return rows, truncated, fmt.Errorf("next: %w", err)
		}
		row, err := tuple.GetAsMap()
		tuple.Close()
		if err != nil {
			return rows, truncated, fmt.Errorf("decode row: %w", err)
		}
		rows = append(rows, row)
	}
	return rows, truncated, nil
}

// execQuery dispatches to Query for no-params and Prepare+Execute for
// parameterized queries.
func execQuery(conn *kuzu.Connection, query string, params map[string]any) (*kuzu.QueryResult, error) {
	if params == nil {
		return conn.Query(query)
	}
	stmt, err := conn.Prepare(query)
	if err != nil {
		return nil, fmt.Errorf("prepare: %w", err)
	}
	defer stmt.Close()
	return conn.Execute(stmt, params)
}

// decodeResult walks the FlatTuple cursor and materialises each row as a
// map keyed by the result's column names. Cells are converted to Go values
// via go-kuzu's built-in kuzuValueToGoValue (exposed through FlatTuple.GetAsMap).
func decodeResult(qr *kuzu.QueryResult) ([]map[string]any, error) {
	var rows []map[string]any
	for qr.HasNext() {
		tuple, err := qr.Next()
		if err != nil {
			return rows, fmt.Errorf("next: %w", err)
		}
		row, err := tuple.GetAsMap()
		tuple.Close()
		if err != nil {
			return rows, fmt.Errorf("decode row: %w", err)
		}
		rows = append(rows, row)
	}
	return rows, nil
}
