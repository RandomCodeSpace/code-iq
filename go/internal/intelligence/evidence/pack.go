// Package evidence ports the runtime-facing evidence pack and assembler from
// src/main/java/.../intelligence/evidence/. The pack bundles everything the
// caller (an MCP client, a REST consumer) needs to understand a symbol or
// file: matched symbols, related files, cross-references, source snippets,
// provenance, and capability notes.
//
// Mirrors EvidencePack.java + EvidencePackAssembler.java; field names match
// the Java record 1:1 so the JSON shape is structurally identical.
package evidence

import (
	"strings"

	"github.com/randomcodespace/codeiq/go/internal/intelligence/lexical"
	"github.com/randomcodespace/codeiq/go/internal/model"
)

// Capability captures the overall analysis fidelity for the primary
// language of the matched symbols. Mirrors Java CapabilityLevel enum
// (re-declared here so the evidence package does not depend on the
// query subpackage just to spell the level out).
type Capability string

const (
	// CapExact — full AST-level analysis available.
	CapExact Capability = "EXACT"
	// CapPartial — grammar-based analysis with structural gaps.
	CapPartial Capability = "PARTIAL"
	// CapLexicalOnly — regex / lexical detection only.
	CapLexicalOnly Capability = "LEXICAL_ONLY"
	// CapUnsupported — no detection at all for this language.
	CapUnsupported Capability = "UNSUPPORTED"
)

// ArtifactMetadata is the provenance projection bundled into every pack.
// Mirrors Java intelligence/provenance/ArtifactMetadata.java. The Go-side
// provenance package is not yet ported, so this lives here as a forward
// declaration; once the dedicated package lands the type will move and an
// alias kept here for backwards compatibility.
//
// Field names map to the Java record component names so cross-port diffs
// stay clean; Capabilities is a free-form map so we do not pull the
// intelligence/query package into the pack's public surface.
type ArtifactMetadata struct {
	Repository    string         `json:"repository,omitempty"`
	Commit        string         `json:"commit,omitempty"`
	BuiltAt       string         `json:"built_at,omitempty"`
	Tooling       map[string]any `json:"tooling,omitempty"`
	Capabilities  map[string]any `json:"capabilities,omitempty"`
	IntegrityHash string         `json:"integrity_hash,omitempty"`
}

// Pack is the runtime-facing evidence pack returned by get_evidence_pack.
// Field names match the Java record EvidencePack 1:1 so the JSON output is
// structurally identical (per phase-3 success gate).
//
// Slice fields are guaranteed non-nil after construction via EmptyPack or
// the Assembler — the MCP envelope contract requires arrays to serialize as
// `[]` rather than `null`.
type Pack struct {
	MatchedSymbols   []*model.CodeNode      `json:"matched_symbols"`
	RelatedFiles     []string               `json:"related_files"`
	References       []*model.CodeNode      `json:"references"`
	Snippets         []lexical.CodeSnippet  `json:"snippets"`
	Provenance       []map[string]any       `json:"provenance"`
	DegradationNotes []string               `json:"degradation_notes"`
	ArtifactMetadata *ArtifactMetadata      `json:"artifact_metadata,omitempty"`
	CapabilityLevel  Capability             `json:"capability_level"`
}

// EmptyPack returns a pack with no matches and (optionally) a single
// degradation note. Mirrors EvidencePack.empty. All slice fields are
// allocated as zero-length (non-nil) so JSON serialization produces `[]`.
func EmptyPack(meta *ArtifactMetadata, note string) Pack {
	notes := []string{}
	if strings.TrimSpace(note) != "" {
		notes = append(notes, note)
	}
	return Pack{
		MatchedSymbols:   []*model.CodeNode{},
		RelatedFiles:     []string{},
		References:       []*model.CodeNode{},
		Snippets:         []lexical.CodeSnippet{},
		Provenance:       []map[string]any{},
		DegradationNotes: notes,
		ArtifactMetadata: meta,
		CapabilityLevel:  CapUnsupported,
	}
}

// Request is the typed input for the assembler. Mirrors Java
// EvidencePackRequest. At least one of Symbol or FilePath must be non-blank.
type Request struct {
	Symbol            string `json:"symbol,omitempty"`
	FilePath          string `json:"file_path,omitempty"`
	MaxSnippetLines   *int   `json:"max_snippet_lines,omitempty"`
	IncludeReferences bool   `json:"include_references,omitempty"`
}

// IsEmpty reports whether the request carries neither a symbol nor a file
// path. Mirrors EvidencePackRequest#isEmpty.
func (r Request) IsEmpty() bool {
	return strings.TrimSpace(r.Symbol) == "" && strings.TrimSpace(r.FilePath) == ""
}
