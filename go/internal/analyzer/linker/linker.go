// Package linker contains cross-file enrichers that run after detectors during
// `codeiq enrich`. Linkers walk the deterministic GraphBuilder snapshot and
// emit additional nodes/edges that span files (e.g. producer→consumer links
// via a shared topic, repository→entity QUERIES edges).
//
// Mirrors src/main/java/io/github/randomcodespace/iq/analyzer/linker/.
package linker

import "github.com/randomcodespace/codeiq/go/internal/model"

// Result is the bag of new nodes + edges a linker contributes.
type Result struct {
	Nodes []*model.CodeNode
	Edges []*model.CodeEdge
}

// Linker mirrors the Java Linker interface. Implementations MUST be
// deterministic — same input slices in must produce identical output every
// time (sort any map iteration before emitting).
type Linker interface {
	Link(nodes []*model.CodeNode, edges []*model.CodeEdge) Result
}
