package io.github.randomcodespace.iq.intelligence.evidence;

import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.lexical.CodeSnippet;
import io.github.randomcodespace.iq.intelligence.provenance.ArtifactMetadata;
import io.github.randomcodespace.iq.model.CodeNode;

import java.util.List;

/**
 * Runtime-facing evidence pack: everything the caller needs to understand a symbol or file.
 *
 * @param matchedSymbols  Nodes whose name matched the requested symbol or file.
 * @param relatedFiles    File paths of related nodes discovered via cross-references.
 * @param references      Related nodes discovered via cross-reference traversal (non-empty when
 *                        {@link EvidencePackRequest#includeReferences()} is true).
 * @param snippets        Bounded source snippets extracted for matched symbols.
 * @param provenance      Provenance maps (one per matched node; may be null entries).
 * @param degradationNotes Human-readable notes explaining capability gaps; empty list when fully capable.
 * @param artifactMetadata Runtime projection of the artifact manifest.
 * @param capabilityLevel  Overall capability level for the primary language of the matched symbols.
 */
public record EvidencePack(
        List<CodeNode> matchedSymbols,
        List<String> relatedFiles,
        List<CodeNode> references,
        List<CodeSnippet> snippets,
        List<java.util.Map<String, Object>> provenance,
        List<String> degradationNotes,
        ArtifactMetadata artifactMetadata,
        CapabilityLevel capabilityLevel
) {
    /** Returns an empty evidence pack — used when no symbols are found. */
    public static EvidencePack empty(ArtifactMetadata artifactMetadata, String degradationNote) {
        List<String> notes = degradationNote != null ? List.of(degradationNote) : List.of();
        return new EvidencePack(
                List.of(), List.of(), List.of(), List.of(), List.of(),
                notes, artifactMetadata, CapabilityLevel.UNSUPPORTED
        );
    }
}
