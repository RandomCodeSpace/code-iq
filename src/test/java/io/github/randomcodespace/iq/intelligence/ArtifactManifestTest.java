package io.github.randomcodespace.iq.intelligence;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ArtifactManifestTest {

    @Test
    void toMap_bundleFormatIsTwo() {
        var manifest = minimalManifest();
        assertThat(manifest.toMap()).containsEntry("bundle_format", ArtifactManifest.BUNDLE_FORMAT_VERSION);
        assertThat(ArtifactManifest.BUNDLE_FORMAT_VERSION).isEqualTo(2);
    }

    @Test
    void toMap_repositoryIdentityIncluded() {
        var identity = new RepositoryIdentity(
                "https://github.com/example/repo", "abc123def", "main", Instant.now());
        var manifest = new ArtifactManifest(ArtifactManifest.BUNDLE_FORMAT_VERSION, "v1", "myproject", "0.1.0", 1,
                Instant.now().toString(), identity, Map.of(), 100L, 200L, true, false, null);

        var map = manifest.toMap();
        assertThat(map).containsKey("repository");
        @SuppressWarnings("unchecked")
        var repo = (Map<String, Object>) map.get("repository");
        assertThat(repo).containsEntry("repo_url", "https://github.com/example/repo");
        assertThat(repo).containsEntry("commit_sha", "abc123def");
        assertThat(repo).containsEntry("branch", "main");
    }

    @Test
    void toMap_nullRepositoryIdentityOmitted() {
        var manifest = new ArtifactManifest(3, null, "proj", "0.1.0", 1,
                Instant.now().toString(), null, Map.of(), 0L, 0L, false, false, null);

        assertThat(manifest.toMap()).doesNotContainKey("repository");
    }

    @Test
    void toMap_nullTagOmitted() {
        var manifest = new ArtifactManifest(3, null, "proj", "0.1.0", 1,
                Instant.now().toString(), null, Map.of(), 0L, 0L, false, false, null);

        assertThat(manifest.toMap()).doesNotContainKey("tag");
    }

    @Test
    void toMap_checksumsPresentWhenProvided() {
        var manifest = new ArtifactManifest(3, "t", "p", "0.1.0", 1,
                Instant.now().toString(), null, Map.of(), 10L, 20L, true, false,
                Map.of("graph.db.zip", "sha256abc"));

        assertThat(manifest.toMap()).containsKey("checksums");
    }

    private ArtifactManifest minimalManifest() {
        return new ArtifactManifest(ArtifactManifest.BUNDLE_FORMAT_VERSION, "latest", "testproject", "0.1.0", 1,
                Instant.now().toString(), null, Map.of(), 42L, 84L, true, false, null);
    }
}
