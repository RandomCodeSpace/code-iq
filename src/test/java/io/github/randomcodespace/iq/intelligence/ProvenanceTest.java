package io.github.randomcodespace.iq.intelligence;

import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProvenanceTest {

    @Test
    void toProperties_populatesAllKeys() {
        var p = new Provenance("https://github.com/example/repo", "abc123", "0.1.0", 1, CapabilityLevel.PARTIAL);
        var props = p.toProperties();

        assertThat(props).containsEntry(Provenance.KEY_REPO_URL, "https://github.com/example/repo");
        assertThat(props).containsEntry(Provenance.KEY_COMMIT_SHA, "abc123");
        assertThat(props).containsEntry(Provenance.KEY_EXTRACTOR_VER, "0.1.0");
        assertThat(props).containsEntry(Provenance.KEY_SCHEMA_VER, 1);
        assertThat(props).containsEntry(Provenance.KEY_CONFIDENCE, "PARTIAL");
    }

    @Test
    void toProperties_skipsNullRepoUrl() {
        var p = new Provenance(null, null, "0.1.0", 1, CapabilityLevel.EXACT);
        var props = p.toProperties();

        assertThat(props).doesNotContainKey(Provenance.KEY_REPO_URL);
        assertThat(props).doesNotContainKey(Provenance.KEY_COMMIT_SHA);
        assertThat(props).containsKey(Provenance.KEY_EXTRACTOR_VER);
    }

    @Test
    void fromProperties_roundTrip() {
        var original = new Provenance("https://github.com/x/y", "sha999", "1.0", 1, CapabilityLevel.EXACT);
        var props = original.toProperties();
        var restored = Provenance.fromProperties(props);

        assertThat(restored).isNotNull();
        assertThat(restored.repositoryUrl()).isEqualTo("https://github.com/x/y");
        assertThat(restored.commitSha()).isEqualTo("sha999");
        assertThat(restored.extractorVersion()).isEqualTo("1.0");
        assertThat(restored.schemaVersion()).isEqualTo(1);
        assertThat(restored.confidence()).isEqualTo(CapabilityLevel.EXACT);
    }

    @Test
    void fromProperties_returnsNullForEmptyMap() {
        assertThat(Provenance.fromProperties(java.util.Map.of())).isNull();
        assertThat(Provenance.fromProperties(null)).isNull();
    }

    @Test
    void codeNode_setAndGetProvenance() {
        var node = new CodeNode("id1", NodeKind.ENDPOINT, "MyEndpoint");
        var p = new Provenance("https://github.com/a/b", "deadbeef", "0.1.0", 1, CapabilityLevel.PARTIAL);

        node.setProvenance(p);

        assertThat(node.getProperties()).containsKey(Provenance.KEY_EXTRACTOR_VER);
        assertThat(node.getProperties()).containsEntry(Provenance.KEY_COMMIT_SHA, "deadbeef");

        Provenance restored = node.getProvenance();
        assertThat(restored).isNotNull();
        assertThat(restored.commitSha()).isEqualTo("deadbeef");
        assertThat(restored.confidence()).isEqualTo(CapabilityLevel.PARTIAL);
    }

    @Test
    void codeNode_setProvenance_isIdempotent() {
        var node = new CodeNode("id2", NodeKind.ENDPOINT, "EP");
        var p1 = new Provenance(null, "sha1", "0.1.0", 1, CapabilityLevel.PARTIAL);
        var p2 = new Provenance(null, "sha2", "0.1.0", 1, CapabilityLevel.EXACT);

        node.setProvenance(p1);
        node.setProvenance(p2);

        assertThat(node.getProvenance().commitSha()).isEqualTo("sha2");
        assertThat(node.getProvenance().confidence()).isEqualTo(CapabilityLevel.EXACT);
    }
}
