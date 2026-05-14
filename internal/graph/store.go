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
	"runtime"
	"sync"
	"time"

	kuzu "github.com/kuzudb/go-kuzu"
)

// DefaultBufferPoolBytes caps Kuzu's buffer pool to 2 GiB by default.
// kuzu.DefaultSystemConfig() allocates 80% of system RAM (~12 GiB on a 15
// GiB host) before any Go-side enrich work runs, leaving insufficient
// headroom for the in-memory enricher pipeline. 2 GiB is enough for
// real-world graphs at ~/projects/-scale (~430k nodes / ~300k edges) while
// keeping the host OOM bar well below ceiling.
const DefaultBufferPoolBytes uint64 = 2 << 30

// defaultMaxThreads returns the per-query thread cap for Kuzu — bounded so
// COPY FROM's working set scales with parallelism in a controlled way.
// min(4, GOMAXPROCS): keeps headroom even on small hosts; 4 is enough to
// saturate IO+CPU for our COPY shape.
func defaultMaxThreads() uint64 {
	n := runtime.GOMAXPROCS(0)
	if n > 4 {
		n = 4
	}
	if n < 1 {
		n = 1
	}
	return uint64(n)
}

// OpenOptions tunes how Open and OpenReadOnly wire the underlying Kuzu
// SystemConfig. Zero-valued fields fall back to safe defaults documented
// alongside each field.
type OpenOptions struct {
	// BufferPoolBytes caps Kuzu's buffer pool in bytes. Zero -> DefaultBufferPoolBytes.
	BufferPoolBytes uint64
	// MaxThreads caps Kuzu's per-query parallelism. Zero -> defaultMaxThreads().
	MaxThreads uint64
	// ReadOnly opens the database in read-only mode.
	ReadOnly bool
	// QueryTimeout, if > 0, sets the per-query wall-clock timeout.
	QueryTimeout time.Duration
}

func (o OpenOptions) resolved() OpenOptions {
	if o.BufferPoolBytes == 0 {
		o.BufferPoolBytes = DefaultBufferPoolBytes
	}
	if o.MaxThreads == 0 {
		o.MaxThreads = defaultMaxThreads()
	}
	return o
}

// Store is the embedded Kuzu graph store facade. It owns one Kuzu database
// and a single long-lived connection. The zero value is not usable — call
// Open or OpenReadOnly to construct.
type Store struct {
	mu       sync.Mutex
	db       *kuzu.Database
	conn     *kuzu.Connection
	path     string
	readOnly bool
}

// Open creates or opens a Kuzu database with safe default OpenOptions
// (capped BufferPoolBytes + MaxThreads). For tuning, see OpenWithOptions.
func Open(path string) (*Store, error) {
	return OpenWithOptions(path, OpenOptions{})
}

// OpenWithOptions creates or opens a Kuzu database, applying any non-zero
// fields of opts. Zero-valued fields fall back to safe defaults — see
// OpenOptions and DefaultBufferPoolBytes.
func OpenWithOptions(path string, opts OpenOptions) (*Store, error) {
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return nil, fmt.Errorf("graph: mkdir parent: %w", err)
	}
	opts = opts.resolved()
	sys := kuzu.DefaultSystemConfig()
	sys.BufferPoolSize = opts.BufferPoolBytes
	sys.MaxNumThreads = opts.MaxThreads
	if opts.ReadOnly {
		sys.ReadOnly = true
	}
	db, err := kuzu.OpenDatabase(path, sys)
	if err != nil {
		return nil, fmt.Errorf("graph: open db: %w", err)
	}
	conn, err := kuzu.OpenConnection(db)
	if err != nil {
		db.Close()
		return nil, fmt.Errorf("graph: open conn: %w", err)
	}
	if opts.QueryTimeout > 0 {
		conn.SetTimeout(uint64(opts.QueryTimeout / time.Millisecond))
	}
	return &Store{db: db, conn: conn, path: path, readOnly: opts.ReadOnly}, nil
}

// OpenReadOnly opens an existing Kuzu store in read-only mode and sets a
// wall-clock timeout on every Cypher query. queryTimeout matches the Java
// DBMS-level `transaction_timeout=30s` cap (Neo4jConfig). Configurable via
// codeiq.yml `mcp.limits.query_timeout`.
//
// All writes from a Store opened this way are rejected at the Cypher
// gateway (Store.Cypher) before they hit Kuzu — the SDK-level read-only
// flag protects on-disk state but does not surface a Go error, it just
// silently no-ops some statements. Belt-and-braces.
//
// queryTimeout <= 0 disables the per-query timeout. Kuzu interprets the
// timeout in milliseconds; we accept a Go duration for ergonomics.
func OpenReadOnly(path string, queryTimeout time.Duration) (*Store, error) {
	return OpenWithOptions(path, OpenOptions{
		ReadOnly:     true,
		QueryTimeout: queryTimeout,
	})
}

// IsReadOnly reports whether the store rejects mutating Cypher.
func (s *Store) IsReadOnly() bool { return s.readOnly }

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
