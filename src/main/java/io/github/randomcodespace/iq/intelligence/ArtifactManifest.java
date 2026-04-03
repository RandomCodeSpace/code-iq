package io.github.randomcodespace.iq.intelligence;

import java.util.Map;

/**
 * Artifact manifest — extends the bundle manifest with repository identity,
 * schema version, extractor version, file inventory summary, and integrity checksums.
 *
 * @param bundleFormat       Always {@code 2} for this record.
 * @param tag                User-supplied bundle tag (may be null).
 * @param project            Project name.
 * @param extractorVersion   Version of the code-iq extractor that built this bundle.
 * @param schemaVersion      Graph schema version at bundle time.
 * @param createdAt          ISO-8601 timestamp.
 * @param repositoryIdentity Git/VCS identity of the analysed repo.
 * @param fileInventorySummary Summary from {@link FileInventory#toSummary()}.
 * @param nodeCount          Total graph nodes.
 * @param edgeCount          Total graph edges.
 * @param includesSource     Whether source files are bundled.
 * @param includesJar        Whether the CLI JAR is bundled.
 * @param checksums          SHA-256 digests of key bundle entries (entry → hex digest).
 */
public record ArtifactManifest(
        int bundleFormat,
        String tag,
        String project,
        String extractorVersion,
        int schemaVersion,
        String createdAt,
        RepositoryIdentity repositoryIdentity,
        Map<String, Object> fileInventorySummary,
        long nodeCount,
        long edgeCount,
        boolean includesSource,
        boolean includesJar,
        Map<String, String> checksums
) {
    public static final int BUNDLE_FORMAT_VERSION = 2;

    /**
     * Serialise to a JSON-friendly {@link Map} (preserves insertion order).
     * Null/empty fields are omitted for a clean manifest.
     */
    public Map<String, Object> toMap() {
        var m = new java.util.LinkedHashMap<String, Object>();
        m.put("bundle_format", bundleFormat);
        if (tag != null) m.put("tag", tag);
        m.put("project", project);
        m.put("extractor_version", extractorVersion);
        m.put("schema_version", schemaVersion);
        m.put("created_at", createdAt);

        // Repository identity
        if (repositoryIdentity != null) {
            var ri = new java.util.LinkedHashMap<String, Object>();
            if (repositoryIdentity.repoUrl() != null)   ri.put("repo_url", repositoryIdentity.repoUrl());
            if (repositoryIdentity.commitSha() != null) ri.put("commit_sha", repositoryIdentity.commitSha());
            if (repositoryIdentity.branch() != null)    ri.put("branch", repositoryIdentity.branch());
            if (repositoryIdentity.buildTimestamp() != null)
                ri.put("build_timestamp", repositoryIdentity.buildTimestamp().toString());
            if (!ri.isEmpty()) m.put("repository", ri);
        }

        // File inventory summary
        if (fileInventorySummary != null && !fileInventorySummary.isEmpty()) {
            m.put("file_inventory", fileInventorySummary);
        }

        m.put("backend", "neo4j");
        m.put("node_count", nodeCount);
        m.put("edge_count", edgeCount);
        m.put("includes_source", includesSource);
        m.put("includes_jar", includesJar);

        if (checksums != null && !checksums.isEmpty()) {
            m.put("checksums", checksums);
        }
        return m;
    }
}
