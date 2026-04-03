package io.github.randomcodespace.iq.intelligence.extractor;

import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.model.CodeEdge;

import java.util.List;
import java.util.Map;

/**
 * Result of a single {@link LanguageExtractor#extract} call.
 *
 * @param callEdges        CALLS edges discovered for this node (method invocations, function calls).
 * @param symbolReferences IMPORTS / DEPENDS_ON edges from import/symbol resolution.
 * @param typeHints        Type annotation key-value pairs to store in node properties
 *                         (e.g. {@code "param_types" -> "int, str"}, {@code "return_type" -> "str"}).
 * @param confidence       Confidence level of this extraction result.
 */
public record LanguageExtractionResult(
        List<CodeEdge> callEdges,
        List<CodeEdge> symbolReferences,
        Map<String, String> typeHints,
        CapabilityLevel confidence
) {
    public LanguageExtractionResult {
        callEdges = List.copyOf(callEdges);
        symbolReferences = List.copyOf(symbolReferences);
        typeHints = Map.copyOf(typeHints);
    }

    /** Empty result with PARTIAL confidence. */
    public static LanguageExtractionResult empty() {
        return new LanguageExtractionResult(List.of(), List.of(), Map.of(), CapabilityLevel.PARTIAL);
    }
}
