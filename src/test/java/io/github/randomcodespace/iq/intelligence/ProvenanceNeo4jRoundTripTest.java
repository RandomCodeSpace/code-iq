package io.github.randomcodespace.iq.intelligence;

import io.github.randomcodespace.iq.graph.GraphRepository;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Verifies the {@code prov_*} → {@code prop_prov_*} → {@code prov_*} Neo4j round-trip.
 * <p>
 * {@link io.github.randomcodespace.iq.graph.GraphStore#bulkSave} stores node properties
 * as {@code prop_<key>} in Neo4j (all values coerced to String via {@code toString()}).
 * {@code nodeFromNeo4j()} restores them by stripping the {@code prop_} prefix.
 * This test verifies that provenance fields survive that transformation, including
 * the {@code schemaVersion} Integer→String→int coercion.
 */
@ExtendWith(MockitoExtension.class)
class ProvenanceNeo4jRoundTripTest {

    @Mock
    private GraphRepository repository;

    @Mock
    private GraphDatabaseService graphDb;

    private GraphStore store;

    @BeforeEach
    void setUp() {
        store = new GraphStore(repository, graphDb);
    }

    @Test
    void provenance_survivesNeo4jRoundTrip() {
        // Arrange: mock a Neo4j node with prop_prov_* keys (what bulkSave writes).
        // bulkSave stores all properties as Strings via .toString(), so schemaVersion
        // is stored as "1" not 1.
        var neo4jNode = mock(org.neo4j.graphdb.Node.class);
        when(neo4jNode.getProperty("id", null)).thenReturn("prov:roundtrip:test");
        when(neo4jNode.getProperty("kind", null)).thenReturn("endpoint");
        when(neo4jNode.getProperty("label", "")).thenReturn("TestEndpoint");
        when(neo4jNode.getProperty("fqn", null)).thenReturn(null);
        when(neo4jNode.getProperty("module", null)).thenReturn(null);
        when(neo4jNode.getProperty("filePath", null)).thenReturn(null);
        when(neo4jNode.getProperty("layer", null)).thenReturn(null);
        when(neo4jNode.getProperty("lineStart", null)).thenReturn(null);
        when(neo4jNode.getProperty("lineEnd", null)).thenReturn(null);
        when(neo4jNode.getProperty("annotations", null)).thenReturn(null);
        // confidence + source are typed first-class fields read by nodeFromNeo4j;
        // this test doesn't care about them, so stub null (legacy/unset) and let the
        // reader fall back to its defaults.
        when(neo4jNode.getProperty("confidence", null)).thenReturn(null);
        when(neo4jNode.getProperty("source", null)).thenReturn(null);

        // Property keys as stored by bulkSave (prop_ prefix, values as String)
        when(neo4jNode.getPropertyKeys()).thenReturn(List.of(
                "prop_prov_repo_url",
                "prop_prov_commit_sha",
                "prop_prov_extractor_version",
                "prop_prov_schema_version",
                "prop_prov_confidence"
        ));
        when(neo4jNode.getProperty("prop_prov_repo_url")).thenReturn("https://github.com/example/repo");
        when(neo4jNode.getProperty("prop_prov_commit_sha")).thenReturn("abc123def456");
        when(neo4jNode.getProperty("prop_prov_extractor_version")).thenReturn("0.1.0-SNAPSHOT");
        when(neo4jNode.getProperty("prop_prov_schema_version")).thenReturn("1"); // String after bulkSave
        when(neo4jNode.getProperty("prop_prov_confidence")).thenReturn("PARTIAL");

        // Mock Transaction: first execute() returns the node, second (edges) returns empty
        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);

        var nodeResult = mock(Result.class);
        when(nodeResult.hasNext()).thenReturn(true, false);
        when(nodeResult.next()).thenReturn(Map.of("n", neo4jNode));

        var edgeResult = mock(Result.class);
        when(edgeResult.hasNext()).thenReturn(false);

        when(tx.execute(anyString(), anyMap())).thenReturn(nodeResult, edgeResult);

        // Act: findById invokes nodeFromNeo4j() internally
        Optional<CodeNode> result = store.findById("prov:roundtrip:test");

        // Assert: node is present and provenance is fully restored
        assertThat(result).isPresent();
        CodeNode node = result.get();
        assertThat(node.getId()).isEqualTo("prov:roundtrip:test");

        Provenance prov = node.getProvenance();
        assertThat(prov).as("Provenance must be restored after Neo4j round-trip").isNotNull();
        assertThat(prov.repositoryUrl()).isEqualTo("https://github.com/example/repo");
        assertThat(prov.commitSha()).isEqualTo("abc123def456");
        assertThat(prov.extractorVersion()).isEqualTo("0.1.0-SNAPSHOT");
        assertThat(prov.schemaVersion()).isEqualTo(1);
        assertThat(prov.confidence()).isEqualTo(CapabilityLevel.PARTIAL);
    }

    @Test
    void provenance_survivesNeo4jRoundTrip_withNullRepoUrl() {
        // Verifies that absent optional fields (repo_url, commit_sha) round-trip as null
        var neo4jNode = mock(org.neo4j.graphdb.Node.class);
        when(neo4jNode.getProperty("id", null)).thenReturn("prov:roundtrip:nullfields");
        when(neo4jNode.getProperty("kind", null)).thenReturn("class");
        when(neo4jNode.getProperty("label", "")).thenReturn("SomeClass");
        when(neo4jNode.getProperty("fqn", null)).thenReturn(null);
        when(neo4jNode.getProperty("module", null)).thenReturn(null);
        when(neo4jNode.getProperty("filePath", null)).thenReturn(null);
        when(neo4jNode.getProperty("layer", null)).thenReturn(null);
        when(neo4jNode.getProperty("lineStart", null)).thenReturn(null);
        when(neo4jNode.getProperty("lineEnd", null)).thenReturn(null);
        when(neo4jNode.getProperty("annotations", null)).thenReturn(null);
        when(neo4jNode.getProperty("confidence", null)).thenReturn(null);
        when(neo4jNode.getProperty("source", null)).thenReturn(null);

        // Only required provenance keys (no repo_url, no commit_sha)
        when(neo4jNode.getPropertyKeys()).thenReturn(List.of(
                "prop_prov_extractor_version",
                "prop_prov_schema_version",
                "prop_prov_confidence"
        ));
        when(neo4jNode.getProperty("prop_prov_extractor_version")).thenReturn("0.1.0-SNAPSHOT");
        when(neo4jNode.getProperty("prop_prov_schema_version")).thenReturn("1");
        when(neo4jNode.getProperty("prop_prov_confidence")).thenReturn("EXACT");

        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        var nodeResult = mock(Result.class);
        when(nodeResult.hasNext()).thenReturn(true, false);
        when(nodeResult.next()).thenReturn(Map.of("n", neo4jNode));
        var edgeResult = mock(Result.class);
        when(edgeResult.hasNext()).thenReturn(false);
        when(tx.execute(anyString(), anyMap())).thenReturn(nodeResult, edgeResult);

        Optional<CodeNode> result = store.findById("prov:roundtrip:nullfields");

        assertThat(result).isPresent();
        Provenance prov = result.get().getProvenance();
        assertThat(prov).isNotNull();
        assertThat(prov.repositoryUrl()).isNull();
        assertThat(prov.commitSha()).isNull();
        assertThat(prov.confidence()).isEqualTo(CapabilityLevel.EXACT);
        assertThat(prov.schemaVersion()).isEqualTo(1);
    }
}
