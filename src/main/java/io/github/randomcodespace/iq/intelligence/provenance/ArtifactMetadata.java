package io.github.randomcodespace.iq.intelligence.provenance;

import io.github.randomcodespace.iq.intelligence.CapabilityLevel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

/**
 * Runtime-facing projection of {@code ArtifactManifest}.
 * Loaded once at {@code serve} startup; immutable and thread-safe.
 *
 * <p>The {@code integrityHash} is a SHA-256 digest of
 * {@code nodeCount + "|" + edgeCount + "|" + commitSha}.
 *
 * @param repositoryIdentity    Remote URL or local path of the analysed repository.
 * @param commitSha             Full SHA-1 of HEAD at analysis time (may be null).
 * @param buildTimestamp        When the {@code enrich} run completed.
 * @param schemaVersion         Graph schema version.
 * @param artifactFormatVersion Bundle format version string.
 * @param extractorVersions     Map of extractor component name → version string.
 * @param languageCapabilities  Per-language capability matrix snapshot.
 * @param integrityHash         SHA-256 integrity hash (hex).
 */
public record ArtifactMetadata(
        String repositoryIdentity,
        String commitSha,
        Instant buildTimestamp,
        String schemaVersion,
        String artifactFormatVersion,
        Map<String, String> extractorVersions,
        Map<String, Map<String, CapabilityLevel>> languageCapabilities,
        String integrityHash
) {
    /**
     * Compute the integrity hash from graph counts and commit SHA.
     * Returns a hex-encoded SHA-256 digest.
     */
    public static String computeIntegrityHash(long nodeCount, long edgeCount, String commitSha) {
        String canonical = nodeCount + "|" + edgeCount + "|" + (commitSha != null ? commitSha : "");
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed by the JDK
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
