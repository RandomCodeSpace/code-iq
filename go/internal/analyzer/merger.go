package analyzer

import (
	"sort"

	"github.com/randomcodespace/codeiq/go/internal/model"
)

// mergeNode merges incoming into existing, picking the higher-confidence
// node as the survivor, then filling gaps and unioning properties /
// annotations. Returns the survivor (which is mutated in place).
//
// Plan §1.1 — semantics:
//   - Higher Confidence wins; ties keep existing.
//   - Non-empty FQN / Module / FilePath / LineStart / LineEnd / Layer
//     fill in from whichever side has them.
//   - Properties: incoming wins per-key only when existing's value is nil
//     or missing (do not clobber framework/auth_type already stamped by a
//     higher-confidence detector).
//   - Annotations are unioned and sorted for determinism.
func mergeNode(existing, incoming *model.CodeNode) *model.CodeNode {
	if existing == nil {
		return incoming
	}
	if incoming == nil {
		return existing
	}

	survivor := existing
	donor := incoming
	if incoming.Confidence > existing.Confidence {
		survivor = incoming
		donor = existing
	}

	// Gap-fill scalar fields from the donor when the survivor has none.
	if survivor.FQN == "" && donor.FQN != "" {
		survivor.FQN = donor.FQN
	}
	if survivor.Module == "" && donor.Module != "" {
		survivor.Module = donor.Module
	}
	if survivor.FilePath == "" && donor.FilePath != "" {
		survivor.FilePath = donor.FilePath
	}
	if survivor.LineStart == 0 && donor.LineStart != 0 {
		survivor.LineStart = donor.LineStart
	}
	if survivor.LineEnd == 0 && donor.LineEnd != 0 {
		survivor.LineEnd = donor.LineEnd
	}
	if survivor.Layer == model.LayerUnknown && donor.Layer != model.LayerUnknown {
		survivor.Layer = donor.Layer
	}
	if survivor.Source == "" && donor.Source != "" {
		survivor.Source = donor.Source
	}

	// Property union: donor fills missing keys; never clobbers existing.
	if survivor.Properties == nil {
		survivor.Properties = map[string]any{}
	}
	for k, v := range donor.Properties {
		if _, exists := survivor.Properties[k]; exists {
			continue
		}
		survivor.Properties[k] = v
	}

	// Annotation union — dedup + sort for determinism.
	survivor.Annotations = unionSorted(survivor.Annotations, donor.Annotations)

	return survivor
}

// mergeEdge merges two edges with the same EdgeKey (src, tgt, kind).
// Higher-confidence wins; ties keep existing. Properties unioned with
// non-clobber semantics.
func mergeEdge(existing, incoming *model.CodeEdge) *model.CodeEdge {
	if existing == nil {
		return incoming
	}
	if incoming == nil {
		return existing
	}

	survivor := existing
	donor := incoming
	if incoming.Confidence > existing.Confidence {
		survivor = incoming
		donor = existing
	}
	if survivor.Source == "" && donor.Source != "" {
		survivor.Source = donor.Source
	}
	if survivor.Properties == nil {
		survivor.Properties = map[string]any{}
	}
	for k, v := range donor.Properties {
		if _, exists := survivor.Properties[k]; exists {
			continue
		}
		survivor.Properties[k] = v
	}
	return survivor
}

func unionSorted(a, b []string) []string {
	seen := make(map[string]struct{}, len(a)+len(b))
	for _, s := range a {
		seen[s] = struct{}{}
	}
	for _, s := range b {
		seen[s] = struct{}{}
	}
	out := make([]string, 0, len(seen))
	for s := range seen {
		out = append(out, s)
	}
	sort.Strings(out)
	return out
}

// edgeKey is the canonical key used to dedupe edges. Two edges with the
// same (source, target, kind) are considered the same edge regardless of
// detector-assigned ID strings.
type edgeKey struct {
	source string
	target string
	kind   model.EdgeKind
}

func makeEdgeKey(e *model.CodeEdge) edgeKey {
	return edgeKey{source: e.SourceID, target: e.TargetID, kind: e.Kind}
}
