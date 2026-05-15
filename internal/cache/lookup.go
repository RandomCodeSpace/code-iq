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
