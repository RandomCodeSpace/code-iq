package cache

import (
	"database/sql"
	"fmt"
	"os"
)

// Stats summarises the cache contents — used by `codeiq cache info`.
type Stats struct {
	FileCount  int   `json:"file_count"`
	NodeCount  int   `json:"node_count"`
	EdgeCount  int   `json:"edge_count"`
	SizeBytes  int64 `json:"size_bytes"`
	Version    int   `json:"version"`
}

// Stats returns the row counts and file-size of the cache database.
func (c *Cache) Stats() (Stats, error) {
	var s Stats
	if err := c.db.QueryRow(`SELECT COUNT(*) FROM files`).Scan(&s.FileCount); err != nil {
		return s, fmt.Errorf("cache: count files: %w", err)
	}
	if err := c.db.QueryRow(`SELECT COUNT(*) FROM nodes`).Scan(&s.NodeCount); err != nil {
		return s, fmt.Errorf("cache: count nodes: %w", err)
	}
	if err := c.db.QueryRow(`SELECT COUNT(*) FROM edges`).Scan(&s.EdgeCount); err != nil {
		return s, fmt.Errorf("cache: count edges: %w", err)
	}
	v, err := c.Version()
	if err == nil {
		s.Version = v
	}
	return s, nil
}

// FileSize returns the cache file size in bytes; 0 when the file does not
// exist. Wrap of os.Stat — exposed as a method so callers don't need to
// know the cache path.
func FileSize(path string) int64 {
	st, err := os.Stat(path)
	if err != nil {
		return 0
	}
	return st.Size()
}

// ListEntry is one summarised row for `codeiq cache list`.
type ListEntry struct {
	ContentHash string `json:"content_hash"`
	Path        string `json:"path"`
	Language    string `json:"language"`
	ParsedAt    string `json:"parsed_at"`
	NodeCount   int    `json:"node_count"`
	EdgeCount   int    `json:"edge_count"`
}

// List returns up to `limit` summarised cache entries ordered by path. Use
// offset to page through the cache. Pass limit <= 0 for an unbounded scan
// (use carefully — large caches can have tens of thousands of rows).
func (c *Cache) List(limit, offset int) ([]ListEntry, error) {
	q := `
		SELECT f.content_hash, f.path, f.language, f.parsed_at,
		       (SELECT COUNT(*) FROM nodes n WHERE n.content_hash = f.content_hash) AS node_count,
		       (SELECT COUNT(*) FROM edges e WHERE e.content_hash = f.content_hash) AS edge_count
		FROM files f
		ORDER BY f.path`
	if limit > 0 {
		q += fmt.Sprintf(" LIMIT %d OFFSET %d", limit, offset)
	}
	rows, err := c.db.Query(q)
	if err != nil {
		return nil, fmt.Errorf("cache: list: %w", err)
	}
	defer rows.Close()
	var out []ListEntry
	for rows.Next() {
		var e ListEntry
		if err := rows.Scan(&e.ContentHash, &e.Path, &e.Language, &e.ParsedAt, &e.NodeCount, &e.EdgeCount); err != nil {
			return nil, fmt.Errorf("cache: scan: %w", err)
		}
		out = append(out, e)
	}
	return out, rows.Err()
}

// Clear truncates every row from files / nodes / edges. The cache_meta
// row (cache version) is preserved so re-opens don't trigger a version
// mismatch. Returns the number of rows deleted from `files` so callers
// can report progress.
func (c *Cache) Clear() (int64, error) {
	tx, err := c.db.Begin()
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	if _, err := tx.Exec(`DELETE FROM edges`); err != nil {
		return 0, err
	}
	if _, err := tx.Exec(`DELETE FROM nodes`); err != nil {
		return 0, err
	}
	res, err := tx.Exec(`DELETE FROM files`)
	if err != nil {
		return 0, err
	}
	if _, err := tx.Exec(`DELETE FROM analysis_runs`); err != nil {
		return 0, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return 0, err
	}
	if err := tx.Commit(); err != nil {
		return 0, err
	}
	return n, nil
}

// LookupByHashOrPath resolves a query against the cache: tries content
// hash first, then file path (full match), then file path suffix. Returns
// the full Entry. When no match is found, returns (nil, sql.ErrNoRows)
// so callers can detect not-found explicitly.
func (c *Cache) LookupByHashOrPath(query string) (*Entry, error) {
	// 1. Exact content hash.
	if c.Has(query) {
		return c.Get(query)
	}
	// 2. Exact path.
	var hash string
	err := c.db.QueryRow(`SELECT content_hash FROM files WHERE path = ? LIMIT 1`, query).Scan(&hash)
	if err == nil {
		return c.Get(hash)
	}
	if err != sql.ErrNoRows {
		return nil, err
	}
	// 3. Path suffix (handy when callers pass a relative path).
	err = c.db.QueryRow(`SELECT content_hash FROM files WHERE path LIKE ? ORDER BY path LIMIT 1`,
		"%"+query).Scan(&hash)
	if err == nil {
		return c.Get(hash)
	}
	return nil, sql.ErrNoRows
}
