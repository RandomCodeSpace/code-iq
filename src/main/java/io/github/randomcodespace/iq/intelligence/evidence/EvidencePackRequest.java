package io.github.randomcodespace.iq.intelligence.evidence;

/**
 * Request parameters for assembling an evidence pack.
 *
 * @param symbol           Symbol name to look up (e.g. "UserService", "handleLogin"). May be null if filePath provided.
 * @param filePath         Source file path relative to repo root. May be null if symbol provided.
 * @param maxSnippetLines  Maximum lines per snippet; null → use config default.
 * @param includeReferences Whether to include cross-reference nodes in the pack.
 */
public record EvidencePackRequest(
        String symbol,
        String filePath,
        Integer maxSnippetLines,
        boolean includeReferences
) {
    /** Returns true when neither symbol nor filePath are provided. */
    public boolean isEmpty() {
        return (symbol == null || symbol.isBlank()) && (filePath == null || filePath.isBlank());
    }
}
