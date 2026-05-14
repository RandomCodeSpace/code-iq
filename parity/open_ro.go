//go:build parity

package parity

import "github.com/randomcodespace/codeiq/internal/cache"

// openCacheRO opens a cache for read access. Phase 1 doesn't distinguish
// read-only -- cache.Open is sufficient. Wraps as a stable seam for phase 2
// when a read-only mode lands.
func openCacheRO(path string) (*cache.Cache, error) {
	return cache.Open(path)
}
