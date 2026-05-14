// Package linker contains cross-file enrichers that run after detectors during
// `codeiq enrich`. Linkers walk the deterministic GraphBuilder snapshot and
// emit additional nodes/edges that span files (e.g. producer→consumer links
// via a shared topic, repository→entity QUERIES edges).
//
// Mirrors src/main/java/io/github/randomcodespace/iq/analyzer/linker/.
package linker

import (
	"sort"

	"github.com/randomcodespace/codeiq/internal/model"
)

// Result is the bag of new nodes + edges a linker contributes.
type Result struct {
	Nodes []*model.CodeNode
	Edges []*model.CodeEdge
}

// Sorted returns r with Nodes and Edges sorted by ID. Plan §1.4 — a
// defensive wrapper applied at the linker boundary so a future linker
// change can't re-introduce drift even if its internal map-iteration
// order shifts.
func (r Result) Sorted() Result {
	sort.SliceStable(r.Nodes, func(i, j int) bool { return r.Nodes[i].ID < r.Nodes[j].ID })
	sort.SliceStable(r.Edges, func(i, j int) bool { return r.Edges[i].ID < r.Edges[j].ID })
	return r
}

// Linker mirrors the Java Linker interface. Implementations MUST be
// deterministic — same input slices in must produce identical output every
// time (sort any map iteration before emitting).
type Linker interface {
	Link(nodes []*model.CodeNode, edges []*model.CodeEdge) Result
}
