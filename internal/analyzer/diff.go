package analyzer

import (
	"fmt"
	"os"

	"github.com/randomcodespace/codeiq/internal/cache"
)

// Delta is the result of comparing the on-disk file set to the cache state.
// All slices are sorted by path (FileDiscovery sorts; AllFiles iterates in
// path order) so callers can rely on stable order.
type Delta struct {
	Added     []string // on disk, not in cache
	Modified  []string // path in cache but content_hash differs from disk
	Deleted   []string // in cache, missing from disk
	Unchanged []string // path + content_hash match cache exactly
}

// Diff walks the project root via FileDiscovery and classifies each file
// against the cache. UNCHANGED files cost one hash per file; nothing else
// is parsed or detected.
//
// Returns Delta with empty slices (not nil) when there is no work in a
// bucket.
func (a *Analyzer) Diff(root string) (Delta, error) {
	d := Delta{}
	if a.opts.Cache == nil {
		return d, fmt.Errorf("diff: cache is required")
	}
	disc := NewFileDiscovery()
	files, err := disc.Discover(root)
	if err != nil {
		return d, fmt.Errorf("file discovery: %w", err)
	}

	seen := make(map[string]bool, len(files))
	for _, f := range files {
		seen[f.RelPath] = true
		content, err := os.ReadFile(f.AbsPath)
		if err != nil {
			fmt.Fprintf(os.Stderr, "codeiq: diff: %s: %v\n", f.RelPath, err)
			continue
		}
		curHash := cache.HashString(string(content))
		cachedHash, _, ok := a.opts.Cache.GetFileByPath(f.RelPath)
		switch {
		case !ok:
			d.Added = append(d.Added, f.RelPath)
		case cachedHash == curHash:
			d.Unchanged = append(d.Unchanged, f.RelPath)
		default:
			d.Modified = append(d.Modified, f.RelPath)
		}
	}

	if err := a.opts.Cache.AllFiles(func(path, _ string) error {
		if !seen[path] {
			d.Deleted = append(d.Deleted, path)
		}
		return nil
	}); err != nil {
		return d, err
	}
	return d, nil
}
