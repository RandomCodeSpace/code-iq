package io.github.randomcodespace.iq.intelligence;

/**
 * Provenance metadata attached to every CodeNode in the intelligence graph.
 * Stored in the node's {@code properties} map using {@code prov_*} keys.
 *
 * @param repositoryUrl    Remote URL of the repository (may be null for local-only analysis).
 * @param commitSha        Full SHA-1 of the HEAD commit at analysis time (may be null).
 * @param extractorVersion Version of the codeiq extractor that produced this node.
 * @param schemaVersion    Graph schema version (integer, incremented on breaking changes).
 * @param confidence       Capability level for the language/feature that produced this node.
 */
public record Provenance(
        String repositoryUrl,
        String commitSha,
        String extractorVersion,
        int schemaVersion,
        CapabilityLevel confidence
) {
    /** Current graph schema version. Increment on any breaking schema change. */
    public static final int CURRENT_SCHEMA_VERSION = 1;

    // --- Property map keys (prov_* prefix for Neo4j round-trip) ---
    public static final String KEY_REPO_URL        = "prov_repo_url";
    public static final String KEY_COMMIT_SHA      = "prov_commit_sha";
    public static final String KEY_EXTRACTOR_VER   = "prov_extractor_version";
    public static final String KEY_SCHEMA_VER      = "prov_schema_version";
    public static final String KEY_CONFIDENCE      = "prov_confidence";

    /**
     * Write this provenance into a node's properties map.
     * Null-valued fields are skipped to avoid polluting the map.
     */
    public java.util.Map<String, Object> toProperties() {
        var map = new java.util.LinkedHashMap<String, Object>();
        if (repositoryUrl != null) map.put(KEY_REPO_URL, repositoryUrl);
        if (commitSha != null)     map.put(KEY_COMMIT_SHA, commitSha);
        map.put(KEY_EXTRACTOR_VER, extractorVersion);
        map.put(KEY_SCHEMA_VER,    schemaVersion);
        map.put(KEY_CONFIDENCE,    confidence.name());
        return map;
    }

    /**
     * Reconstruct a Provenance from a node's properties map.
     * Returns null if provenance keys are absent.
     */
    public static Provenance fromProperties(java.util.Map<String, Object> props) {
        if (props == null || !props.containsKey(KEY_EXTRACTOR_VER)) return null;
        String repoUrl      = (String) props.get(KEY_REPO_URL);
        String sha          = (String) props.get(KEY_COMMIT_SHA);
        String extVer       = (String) props.getOrDefault(KEY_EXTRACTOR_VER, "unknown");
        Object schemaVerObj = props.getOrDefault(KEY_SCHEMA_VER, CURRENT_SCHEMA_VERSION);
        int schemaVer = schemaVerObj instanceof Number n ? n.intValue()
                : Integer.parseInt(schemaVerObj.toString());
        String confStr      = (String) props.getOrDefault(KEY_CONFIDENCE, CapabilityLevel.PARTIAL.name());
        CapabilityLevel confidence;
        try {
            confidence = CapabilityLevel.valueOf(confStr);
        } catch (IllegalArgumentException e) {
            confidence = CapabilityLevel.PARTIAL;
        }
        return new Provenance(repoUrl, sha, extVer, schemaVer, confidence);
    }
}
