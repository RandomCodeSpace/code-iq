// Package parity contains the cross-binary diff harness. Phase 1 dumps the
// SQLite cache to a normalized JSON form; phase 2 extends to the Kuzu graph.
package parity

import (
	"encoding/json"
	"sort"

	"github.com/randomcodespace/codeiq/go/internal/cache"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// NormalizedEntry is the diff-friendly shape of a cache entry. Volatile
// fields (parsed_at timestamp) are dropped — they're never equal across
// runs of two different binaries.
type NormalizedEntry struct {
	Path     string            `json:"path"`
	Language string            `json:"language"`
	Nodes    []*model.CodeNode `json:"nodes"`
	Edges    []*model.CodeEdge `json:"edges"`
}

// Normalize reads every entry from c and returns a sorted, parsed_at-stripped
// JSON dump suitable for byte-level diffing.
func Normalize(c *cache.Cache) (string, error) {
	var entries []NormalizedEntry
	err := c.IterateAll(func(e *cache.Entry) error {
		ne := NormalizedEntry{
			Path:     e.Path,
			Language: e.Language,
			Nodes:    sortNodes(e.Nodes),
			Edges:    sortEdges(e.Edges),
		}
		entries = append(entries, ne)
		return nil
	})
	if err != nil {
		return "", err
	}
	sort.Slice(entries, func(i, j int) bool { return entries[i].Path < entries[j].Path })
	b, err := json.MarshalIndent(entries, "", "  ")
	if err != nil {
		return "", err
	}
	return string(b), nil
}

func sortNodes(in []*model.CodeNode) []*model.CodeNode {
	out := make([]*model.CodeNode, len(in))
	copy(out, in)
	sort.Slice(out, func(i, j int) bool {
		if out[i].Kind.String() != out[j].Kind.String() {
			return out[i].Kind.String() < out[j].Kind.String()
		}
		return out[i].ID < out[j].ID
	})
	return out
}

func sortEdges(in []*model.CodeEdge) []*model.CodeEdge {
	out := make([]*model.CodeEdge, len(in))
	copy(out, in)
	sort.Slice(out, func(i, j int) bool {
		if out[i].Kind.String() != out[j].Kind.String() {
			return out[i].Kind.String() < out[j].Kind.String()
		}
		if out[i].SourceID != out[j].SourceID {
			return out[i].SourceID < out[j].SourceID
		}
		return out[i].TargetID < out[j].TargetID
	})
	return out
}
