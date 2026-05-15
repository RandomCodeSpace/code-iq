package cache

// GetFileByPath returns the cached (content_hash, parsed_at) for the given
// file path. Returns ok=false when no row exists for that path.
func (c *Cache) GetFileByPath(path string) (hash, parsedAt string, ok bool) {
	row := c.db.QueryRow(`SELECT content_hash, parsed_at FROM files WHERE path = ? LIMIT 1`, path)
	if err := row.Scan(&hash, &parsedAt); err != nil {
		return "", "", false
	}
	return hash, parsedAt, true
}

// AllFiles invokes fn once per cached file in path order. fn returning a
// non-nil error stops iteration and propagates the error. Stream-iterated
// via rows.Next(); the whole cache never lives in memory at once.
func (c *Cache) AllFiles(fn func(path, hash string) error) error {
	rows, err := c.db.Query(`SELECT path, content_hash FROM files ORDER BY path`)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var path, hash string
		if err := rows.Scan(&path, &hash); err != nil {
			return err
		}
		if err := fn(path, hash); err != nil {
			return err
		}
	}
	return rows.Err()
}
