package io.github.randomcodespace.iq.intelligence.provenance;

import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ArtifactMetadata} record and its static helper.
 */
class ArtifactMetadataTest {

    @Test
    void computeIntegrityHashProducesSHA256HexString() {
        String hash = ArtifactMetadata.computeIntegrityHash(100L, 200L, "abc123");
        assertNotNull(hash);
        assertEquals(64, hash.length(), "SHA-256 hex should be 64 chars");
        assertTrue(hash.matches("[0-9a-f]+"), "Hash should be lowercase hex");
    }

    @Test
    void computeIntegrityHashIsDeterministic() {
        String h1 = ArtifactMetadata.computeIntegrityHash(42L, 7L, "deadbeef");
        String h2 = ArtifactMetadata.computeIntegrityHash(42L, 7L, "deadbeef");
        assertEquals(h1, h2);
    }

    @Test
    void computeIntegrityHashDiffersForDifferentInputs() {
        String h1 = ArtifactMetadata.computeIntegrityHash(1L, 2L, "sha1");
        String h2 = ArtifactMetadata.computeIntegrityHash(1L, 2L, "sha2");
        assertNotEquals(h1, h2);

        String h3 = ArtifactMetadata.computeIntegrityHash(1L, 3L, "sha1");
        assertNotEquals(h1, h3);

        String h4 = ArtifactMetadata.computeIntegrityHash(2L, 2L, "sha1");
        assertNotEquals(h1, h4);
    }

    @Test
    void computeIntegrityHashHandlesNullCommitSha() {
        // Should not throw; null sha is treated as empty string
        String hash = ArtifactMetadata.computeIntegrityHash(10L, 20L, null);
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    @Test
    void computeIntegrityHashNullAndEmptyCommitShaDiffer() {
        String withNull = ArtifactMetadata.computeIntegrityHash(1L, 1L, null);
        // null → "" canonical, so same as empty string
        String withEmpty = ArtifactMetadata.computeIntegrityHash(1L, 1L, "");
        assertEquals(withNull, withEmpty, "null commit SHA should be treated as empty string");
    }

    @Test
    void recordConstructorAndAccessors() {
        Instant now = Instant.now();
        Map<String, String> extractors = Map.of("code-iq", "phase-4");
        Map<String, Map<String, CapabilityLevel>> caps = Map.of();

        ArtifactMetadata meta = new ArtifactMetadata(
                "https://github.com/example/repo",
                "abc123",
                now,
                "1",
                "1.0",
                extractors,
                caps,
                "deadbeef"
        );

        assertEquals("https://github.com/example/repo", meta.repositoryIdentity());
        assertEquals("abc123", meta.commitSha());
        assertEquals(now, meta.buildTimestamp());
        assertEquals("1", meta.schemaVersion());
        assertEquals("1.0", meta.artifactFormatVersion());
        assertEquals(extractors, meta.extractorVersions());
        assertEquals(caps, meta.languageCapabilities());
        assertEquals("deadbeef", meta.integrityHash());
    }

    @Test
    void recordEquality() {
        Instant now = Instant.now();
        ArtifactMetadata m1 = new ArtifactMetadata("url", "sha", now, "1", "1.0",
                Map.of(), Map.of(), "hash");
        ArtifactMetadata m2 = new ArtifactMetadata("url", "sha", now, "1", "1.0",
                Map.of(), Map.of(), "hash");

        assertEquals(m1, m2);
        assertEquals(m1.hashCode(), m2.hashCode());
    }
}
