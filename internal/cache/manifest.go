package cache

import (
	"crypto/sha256"
	"encoding/hex"
)

// ManifestHash returns a deterministic SHA-256 hex digest over the sorted
// (path, content_hash) tuples in the cache. Two caches produce the same
// manifest iff they hold the same set of file→hash bindings.
//
// Used by the enrich pipeline to short-circuit: if the graph's stored
// manifest matches the cache's current manifest, the graph is fresh and
// enrich can exit immediately.
//
// Format: "<path>\x00<hash>\x00" per row, concatenated in path order.
// The NUL separator makes collisions impossible regardless of path chars.
func (c *Cache) ManifestHash() (string, error) {
	h := sha256.New()
	err := c.AllFiles(func(path, hash string) error {
		h.Write([]byte(path))
		h.Write([]byte{0})
		h.Write([]byte(hash))
		h.Write([]byte{0})
		return nil
	})
	if err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}
