// Package graph is the Go port's facade over Kuzu Embedded. It mirrors the
// responsibilities of the Java GraphStore: open/close an embedded database,
// run Cypher, bulk-load nodes and edges, and expose read helpers. Writes
// happen during `enrich`; the `serve`/read-side commands open the same
// directory in normal (read-write) mode and issue queries.
//
// Concurrency model: the Store owns one Kuzu database and one long-lived
// connection. All writes funnel through the Store's mutex; reads use the
// same lock today and may relax to a read-write lock later if profiling
// demands it. Kuzu's own connection layer is not thread-safe for parallel
// query execution, so we serialize at this layer.
package graph

import (
	"fmt"
	"os"
	"path/filepath"
	"sync"

	kuzu "github.com/kuzudb/go-kuzu"
)

// Store is the embedded Kuzu graph store facade. It owns one Kuzu database
// and a single long-lived connection. The zero value is not usable — call
// Open to construct.
type Store struct {
	mu   sync.Mutex
	db   *kuzu.Database
	conn *kuzu.Connection
	path string
}

// Open creates or opens a Kuzu database at the given directory path. Kuzu
// itself creates the directory if it does not exist; we ensure the parent
// exists so a fresh `.codeiq/graph/codeiq.kuzu/` works on first run.
func Open(path string) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, fmt.Errorf("graph: mkdir parent: %w", err)
	}
	sys := kuzu.DefaultSystemConfig()
	db, err := kuzu.OpenDatabase(path, sys)
	if err != nil {
		return nil, fmt.Errorf("graph: open db: %w", err)
	}
	conn, err := kuzu.OpenConnection(db)
	if err != nil {
		db.Close()
		return nil, fmt.Errorf("graph: open conn: %w", err)
	}
	return &Store{db: db, conn: conn, path: path}, nil
}

// Close releases the connection and database. Safe to call multiple times;
// the second and subsequent calls are no-ops.
func (s *Store) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.conn != nil {
		s.conn.Close()
		s.conn = nil
	}
	if s.db != nil {
		s.db.Close()
		s.db = nil
	}
	return nil
}

// Path returns the directory the store was opened against.
func (s *Store) Path() string { return s.path }

// Conn returns the underlying Kuzu connection. Callers that need to
// orchestrate multi-statement work directly against go-kuzu can take this,
// but they MUST hold s.Lock()/s.Unlock() around the work. For single-shot
// queries prefer the package helpers (Cypher, etc.) which lock for the
// caller.
func (s *Store) Conn() *kuzu.Connection { return s.conn }

// Lock acquires the store mutex. Exposed for callers that drive the
// connection directly (rare — Cypher / BulkLoad / etc. lock internally).
func (s *Store) Lock() { s.mu.Lock() }

// Unlock releases the store mutex paired with Lock.
func (s *Store) Unlock() { s.mu.Unlock() }
