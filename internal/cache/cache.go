package cache

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"

	_ "github.com/mattn/go-sqlite3"
	"github.com/randomcodespace/codeiq/internal/model"
)

// ErrNotFound is returned by Get when no row matches the content hash.
var ErrNotFound = errors.New("cache: not found")

// Entry is a single file's cached detector results, keyed by content hash.
type Entry struct {
	ContentHash string
	Path        string
	Language    string
	ParsedAt    string // RFC3339
	Nodes       []*model.CodeNode
	Edges       []*model.CodeEdge
}

// Cache is a SQLite-backed analysis cache. Safe for concurrent reads.
// Writes serialize via SQLite's WAL mode + busy_timeout.
type Cache struct {
	db *sql.DB
}

// Open opens or creates the cache file at path. Applies schema + WAL pragmas
// + stamps CacheVersion into cache_meta on first open.
func Open(path string) (*Cache, error) {
	dsn := fmt.Sprintf("file:%s?_journal=WAL&_busy_timeout=5000&_fk=1", path)
	db, err := sql.Open("sqlite3", dsn)
	if err != nil {
		return nil, fmt.Errorf("cache open: %w", err)
	}
	if _, err := db.Exec(pragmasDDL); err != nil {
		db.Close()
		return nil, fmt.Errorf("cache pragmas: %w", err)
	}
	if _, err := db.Exec(schemaDDL); err != nil {
		db.Close()
		return nil, fmt.Errorf("cache schema: %w", err)
	}
	c := &Cache{db: db}
	if err := c.stampVersion(); err != nil {
		db.Close()
		return nil, err
	}
	return c, nil
}

// Close releases the underlying database handle.
func (c *Cache) Close() error { return c.db.Close() }

func (c *Cache) stampVersion() error {
	_, err := c.db.Exec(
		`INSERT INTO cache_meta(meta_key, meta_value) VALUES('version', ?)
		 ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value`,
		fmt.Sprintf("%d", CacheVersion),
	)
	return err
}

// Version reads the cache_version row.
func (c *Cache) Version() (int, error) {
	var s string
	err := c.db.QueryRow(`SELECT meta_value FROM cache_meta WHERE meta_key='version'`).Scan(&s)
	if err != nil {
		return 0, err
	}
	var v int
	if _, err := fmt.Sscanf(s, "%d", &v); err != nil {
		return 0, err
	}
	return v, nil
}

// Has reports whether an entry for contentHash exists.
func (c *Cache) Has(contentHash string) bool {
	var n int
	_ = c.db.QueryRow(`SELECT COUNT(*) FROM files WHERE content_hash=?`, contentHash).Scan(&n)
	return n > 0
}

// Put stores or replaces the cache entry. Atomic — all rows for the hash are
// wiped first then re-inserted in a single transaction.
func (c *Cache) Put(e *Entry) error {
	tx, err := c.db.Begin()
	if err != nil {
		return err
	}
	defer tx.Rollback()

	if _, err := tx.Exec(`DELETE FROM nodes WHERE content_hash=?`, e.ContentHash); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM edges WHERE content_hash=?`, e.ContentHash); err != nil {
		return err
	}
	if _, err := tx.Exec(`DELETE FROM files WHERE content_hash=?`, e.ContentHash); err != nil {
		return err
	}
	if _, err := tx.Exec(
		`INSERT INTO files(content_hash, path, language, parsed_at) VALUES(?,?,?,?)`,
		e.ContentHash, e.Path, e.Language, e.ParsedAt,
	); err != nil {
		return err
	}
	for _, n := range e.Nodes {
		data, err := json.Marshal(n)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(
			`INSERT INTO nodes(id, content_hash, kind, data) VALUES(?,?,?,?)`,
			n.ID, e.ContentHash, n.Kind.String(), string(data),
		); err != nil {
			return err
		}
	}
	for _, ed := range e.Edges {
		data, err := json.Marshal(ed)
		if err != nil {
			return err
		}
		if _, err := tx.Exec(
			`INSERT INTO edges(source, target, content_hash, kind, data) VALUES(?,?,?,?,?)`,
			ed.SourceID, ed.TargetID, e.ContentHash, ed.Kind.String(), string(data),
		); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// Get fetches the cache entry by content hash. Returns ErrNotFound if absent.
func (c *Cache) Get(contentHash string) (*Entry, error) {
	var e Entry
	e.ContentHash = contentHash
	err := c.db.QueryRow(
		`SELECT path, language, parsed_at FROM files WHERE content_hash=?`,
		contentHash,
	).Scan(&e.Path, &e.Language, &e.ParsedAt)
	if err == sql.ErrNoRows {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	rows, err := c.db.Query(`SELECT data FROM nodes WHERE content_hash=? ORDER BY row_id`, contentHash)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var data string
		if err := rows.Scan(&data); err != nil {
			return nil, err
		}
		var n model.CodeNode
		if err := json.Unmarshal([]byte(data), &n); err != nil {
			return nil, err
		}
		e.Nodes = append(e.Nodes, &n)
	}
	erows, err := c.db.Query(`SELECT data FROM edges WHERE content_hash=?`, contentHash)
	if err != nil {
		return nil, err
	}
	defer erows.Close()
	for erows.Next() {
		var data string
		if err := erows.Scan(&data); err != nil {
			return nil, err
		}
		var ed model.CodeEdge
		if err := json.Unmarshal([]byte(data), &ed); err != nil {
			return nil, err
		}
		e.Edges = append(e.Edges, &ed)
	}
	return &e, nil
}

// IterateAll yields every cached entry in deterministic order (sorted by
// path then content_hash) — used by phase 2's enrich.
func (c *Cache) IterateAll(fn func(*Entry) error) error {
	rows, err := c.db.Query(
		`SELECT content_hash FROM files ORDER BY path, content_hash`,
	)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var h string
		if err := rows.Scan(&h); err != nil {
			return err
		}
		e, err := c.Get(h)
		if err != nil {
			return err
		}
		if err := fn(e); err != nil {
			return err
		}
	}
	return nil
}
