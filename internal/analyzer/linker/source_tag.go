package linker

// Source-tag constants stamped onto every edge a linker emits. They give
// the incremental enrich pipeline a way to clear previous linker output
// (via Store.WipeLinkerEdges) before re-running linkers, without touching
// detector-emitted edges.
const (
	SrcTopicLinker             = "linker:topic"
	SrcEntityLinker            = "linker:entity"
	SrcModuleContainmentLinker = "linker:module_containment"
)

// AllSources lists every linker source tag. Store.WipeLinkerEdges takes
// this slice (or a subset) when clearing linker output. Add new entries
// here when adding a new linker.
var AllSources = []string{
	SrcTopicLinker,
	SrcEntityLinker,
	SrcModuleContainmentLinker,
}
