package io.github.randomcodespace.iq.graph;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.Confidence;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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
 * Aggressive Neo4j round-trip coverage for {@link CodeNode#getConfidence()} +
 * {@link CodeNode#getSource()} (and the same on {@link CodeEdge}). Verifies:
 * <ul>
 *   <li>All three {@link Confidence} values round-trip cleanly on nodes</li>
 *   <li>Missing properties (legacy data) fall back to {@code LEXICAL} / {@code null}
 *       — never throw, never null-pointer the typed field</li>
 *   <li>Malformed / mixed-case confidence strings are tolerated</li>
 *   <li>Edge confidence + source round-trip through {@code hydrateEdgesForNode}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class GraphStoreConfidenceRoundTripTest {

    @Mock
    private GraphRepository repository;

    @Mock
    private GraphDatabaseService graphDb;

    private GraphStore store;

    @BeforeEach
    void setUp() {
        store = new GraphStore(repository, graphDb);
    }

    // ---------- Node read path: nodeFromNeo4j() via findById() ----------

    @ParameterizedTest
    @EnumSource(Confidence.class)
    void node_allConfidenceValuesRoundTrip(Confidence value) {
        var neo4jNode = stubBareNeo4jNode("node:Foo.java:class:Foo", "class", "Foo");
        when(neo4jNode.getProperty("confidence", null)).thenReturn(value.name());
        when(neo4jNode.getProperty("source", null)).thenReturn("SpringServiceDetector");
        when(neo4jNode.getPropertyKeys()).thenReturn(List.of());
        wireFindByIdResult(neo4jNode);

        Optional<CodeNode> result = store.findById("node:Foo.java:class:Foo");

        assertThat(result).isPresent();
        assertThat(result.get().getConfidence())
                .as("confidence round-trips through Neo4j read path")
                .isEqualTo(value);
        assertThat(result.get().getSource()).isEqualTo("SpringServiceDetector");
    }

    @Test
    void node_legacyMissingConfidenceFallsBackToLexical() {
        // Simulates a node persisted before this field existed: confidence + source
        // are absent. Reader must default to LEXICAL (least committal) and null.
        var neo4jNode = stubBareNeo4jNode("node:Legacy.java:class:Legacy", "class", "Legacy");
        when(neo4jNode.getProperty("confidence", null)).thenReturn(null);
        when(neo4jNode.getProperty("source", null)).thenReturn(null);
        when(neo4jNode.getPropertyKeys()).thenReturn(List.of());
        wireFindByIdResult(neo4jNode);

        Optional<CodeNode> result = store.findById("node:Legacy.java:class:Legacy");

        assertThat(result).isPresent();
        assertThat(result.get().getConfidence())
                .as("missing confidence in Neo4j defaults to LEXICAL — never null")
                .isEqualTo(Confidence.LEXICAL);
        assertThat(result.get().getSource())
                .as("missing source stays null — no string sentinel")
                .isNull();
    }

    @Test
    void node_legacyHasSourceButMissingConfidence() {
        // Mixed legacy: source got populated some other way but confidence wasn't.
        // Source preserved, confidence still falls back.
        var neo4jNode = stubBareNeo4jNode("node:Mixed.java:class:Mixed", "class", "Mixed");
        when(neo4jNode.getProperty("confidence", null)).thenReturn(null);
        when(neo4jNode.getProperty("source", null)).thenReturn("PartialMigrationDetector");
        when(neo4jNode.getPropertyKeys()).thenReturn(List.of());
        wireFindByIdResult(neo4jNode);

        Optional<CodeNode> result = store.findById("node:Mixed.java:class:Mixed");

        assertThat(result).isPresent();
        assertThat(result.get().getConfidence()).isEqualTo(Confidence.LEXICAL);
        assertThat(result.get().getSource()).isEqualTo("PartialMigrationDetector");
    }

    @Test
    void node_malformedConfidenceFallsBackToLexicalWithoutThrowing() {
        // A garbled write or a future enum addition that hasn't shipped here yet:
        // the reader must not throw — it falls back to LEXICAL silently.
        var neo4jNode = stubBareNeo4jNode("node:Garbled.java:class:Garbled", "class", "Garbled");
        when(neo4jNode.getProperty("confidence", null)).thenReturn("PERFECT"); // not in enum
        when(neo4jNode.getProperty("source", null)).thenReturn(null);
        when(neo4jNode.getPropertyKeys()).thenReturn(List.of());
        wireFindByIdResult(neo4jNode);

        // Must not throw IllegalArgumentException
        Optional<CodeNode> result = store.findById("node:Garbled.java:class:Garbled");

        assertThat(result).isPresent();
        assertThat(result.get().getConfidence())
                .as("unknown confidence string falls back to LEXICAL — read path is non-throwing")
                .isEqualTo(Confidence.LEXICAL);
    }

    @Test
    void node_mixedCaseConfidenceParsesCorrectly() {
        // Confidence.fromString is case-insensitive — verify the read path uses it.
        var neo4jNode = stubBareNeo4jNode("node:Mixed.java:class:Mixed", "class", "Mixed");
        when(neo4jNode.getProperty("confidence", null)).thenReturn("ReSoLvEd");
        when(neo4jNode.getProperty("source", null)).thenReturn("CaseTestDetector");
        when(neo4jNode.getPropertyKeys()).thenReturn(List.of());
        wireFindByIdResult(neo4jNode);

        Optional<CodeNode> result = store.findById("node:Mixed.java:class:Mixed");

        assertThat(result).isPresent();
        assertThat(result.get().getConfidence()).isEqualTo(Confidence.RESOLVED);
    }

    @Test
    void node_emptyStringSourcePreservedAsEmpty() {
        // Defensive: if upstream wrote an empty string, we don't silently turn it
        // into null — the field reads back as empty string. (Detectors should never
        // emit empty source, but the read path stays faithful.)
        var neo4jNode = stubBareNeo4jNode("node:Empty.java:class:Empty", "class", "Empty");
        when(neo4jNode.getProperty("confidence", null)).thenReturn("LEXICAL");
        when(neo4jNode.getProperty("source", null)).thenReturn("");
        when(neo4jNode.getPropertyKeys()).thenReturn(List.of());
        wireFindByIdResult(neo4jNode);

        Optional<CodeNode> result = store.findById("node:Empty.java:class:Empty");

        assertThat(result).isPresent();
        assertThat(result.get().getSource()).isEmpty();
    }

    // ---------- Edge read path: hydrateEdgesForNode() via findById() ----------

    @Test
    void edge_confidenceAndSourceRoundTrip() {
        // findById hydrates outgoing edges. Mock both the node lookup AND the edge query.
        var neo4jNode = stubBareNeo4jNode("node:Foo.java:class:Foo", "class", "Foo");
        when(neo4jNode.getProperty("confidence", null)).thenReturn("RESOLVED");
        when(neo4jNode.getProperty("source", null)).thenReturn("SpringServiceDetector");
        when(neo4jNode.getPropertyKeys()).thenReturn(List.of());

        var targetNeo4j = stubBareNeo4jNode("node:Bar.java:class:Bar", "class", "Bar");
        when(targetNeo4j.getProperty("confidence", null)).thenReturn(null);
        when(targetNeo4j.getProperty("source", null)).thenReturn(null);
        when(targetNeo4j.getPropertyKeys()).thenReturn(List.of());

        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);

        // First execute(): node lookup
        var nodeResult = mock(Result.class);
        when(nodeResult.hasNext()).thenReturn(true, false);
        when(nodeResult.next()).thenReturn(Map.of("n", neo4jNode));

        // Second execute(): outgoing edges
        var edgeResult = mock(Result.class);
        when(edgeResult.hasNext()).thenReturn(true, false);
        when(edgeResult.next()).thenReturn(Map.of(
                "id", "edge:Foo->Bar:depends_on",
                "kind", "depends_on",
                "targetId", "node:Bar.java:class:Bar",
                "t", targetNeo4j,
                "confidence", "RESOLVED",
                "source", "SpringDependsOnDetector"
        ));

        when(tx.execute(anyString(), anyMap())).thenReturn(nodeResult, edgeResult);

        Optional<CodeNode> result = store.findById("node:Foo.java:class:Foo");

        assertThat(result).isPresent();
        assertThat(result.get().getEdges()).hasSize(1);
        CodeEdge edge = result.get().getEdges().getFirst();
        assertThat(edge.getConfidence()).isEqualTo(Confidence.RESOLVED);
        assertThat(edge.getSource()).isEqualTo("SpringDependsOnDetector");
    }

    @Test
    void edge_legacyMissingConfidenceAndSourceFallsBackCleanly() {
        var neo4jNode = stubBareNeo4jNode("node:Foo.java:class:Foo", "class", "Foo");
        when(neo4jNode.getProperty("confidence", null)).thenReturn(null);
        when(neo4jNode.getProperty("source", null)).thenReturn(null);
        when(neo4jNode.getPropertyKeys()).thenReturn(List.of());

        var targetNeo4j = stubBareNeo4jNode("node:Bar.java:class:Bar", "class", "Bar");
        when(targetNeo4j.getProperty("confidence", null)).thenReturn(null);
        when(targetNeo4j.getProperty("source", null)).thenReturn(null);
        when(targetNeo4j.getPropertyKeys()).thenReturn(List.of());

        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);

        var nodeResult = mock(Result.class);
        when(nodeResult.hasNext()).thenReturn(true, false);
        when(nodeResult.next()).thenReturn(Map.of("n", neo4jNode));

        // Edge row missing confidence + source keys (legacy edge). Map.of cannot
        // contain nulls, so we use HashMap-style construction via java.util.HashMap.
        java.util.HashMap<String, Object> legacyEdgeRow = new java.util.HashMap<>();
        legacyEdgeRow.put("id", "edge:Foo->Bar:legacy");
        legacyEdgeRow.put("kind", "depends_on");
        legacyEdgeRow.put("targetId", "node:Bar.java:class:Bar");
        legacyEdgeRow.put("t", targetNeo4j);
        legacyEdgeRow.put("confidence", null);
        legacyEdgeRow.put("source", null);

        var edgeResult = mock(Result.class);
        when(edgeResult.hasNext()).thenReturn(true, false);
        when(edgeResult.next()).thenReturn(legacyEdgeRow);

        when(tx.execute(anyString(), anyMap())).thenReturn(nodeResult, edgeResult);

        Optional<CodeNode> result = store.findById("node:Foo.java:class:Foo");

        assertThat(result).isPresent();
        assertThat(result.get().getEdges()).hasSize(1);
        CodeEdge edge = result.get().getEdges().getFirst();
        assertThat(edge.getConfidence())
                .as("legacy edge missing confidence falls back to LEXICAL")
                .isEqualTo(Confidence.LEXICAL);
        assertThat(edge.getSource())
                .as("legacy edge missing source stays null")
                .isNull();
    }

    @Test
    void edge_malformedConfidenceFallsBackToLexicalWithoutThrowing() {
        var neo4jNode = stubBareNeo4jNode("node:Foo.java:class:Foo", "class", "Foo");
        when(neo4jNode.getProperty("confidence", null)).thenReturn(null);
        when(neo4jNode.getProperty("source", null)).thenReturn(null);
        when(neo4jNode.getPropertyKeys()).thenReturn(List.of());

        var targetNeo4j = stubBareNeo4jNode("node:Bar.java:class:Bar", "class", "Bar");
        when(targetNeo4j.getProperty("confidence", null)).thenReturn(null);
        when(targetNeo4j.getProperty("source", null)).thenReturn(null);
        when(targetNeo4j.getPropertyKeys()).thenReturn(List.of());

        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);

        var nodeResult = mock(Result.class);
        when(nodeResult.hasNext()).thenReturn(true, false);
        when(nodeResult.next()).thenReturn(Map.of("n", neo4jNode));

        var edgeResult = mock(Result.class);
        when(edgeResult.hasNext()).thenReturn(true, false);
        when(edgeResult.next()).thenReturn(Map.of(
                "id", "edge:Foo->Bar:garbled",
                "kind", "depends_on",
                "targetId", "node:Bar.java:class:Bar",
                "t", targetNeo4j,
                "confidence", "PERFECT", // not a Confidence enum
                "source", "GarbledDetector"
        ));

        when(tx.execute(anyString(), anyMap())).thenReturn(nodeResult, edgeResult);

        Optional<CodeNode> result = store.findById("node:Foo.java:class:Foo");

        assertThat(result).isPresent();
        CodeEdge edge = result.get().getEdges().getFirst();
        assertThat(edge.getConfidence())
                .as("garbled enum string does not throw — falls back to LEXICAL")
                .isEqualTo(Confidence.LEXICAL);
        assertThat(edge.getSource())
                .as("source is preserved even when confidence is garbled")
                .isEqualTo("GarbledDetector");
    }

    // ---------- Helpers ----------

    /**
     * Build a Neo4j Node mock with the standard non-confidence-related getProperty
     * stubs already wired (id, kind, label, fqn, module, filePath, layer, lineStart,
     * lineEnd, annotations). Caller adds confidence + source + propertyKeys stubs.
     */
    private static org.neo4j.graphdb.Node stubBareNeo4jNode(String id, String kindStr, String label) {
        var n = mock(org.neo4j.graphdb.Node.class);
        when(n.getProperty("id", null)).thenReturn(id);
        when(n.getProperty("kind", null)).thenReturn(kindStr);
        when(n.getProperty("label", "")).thenReturn(label);
        when(n.getProperty("fqn", null)).thenReturn(null);
        when(n.getProperty("module", null)).thenReturn(null);
        when(n.getProperty("filePath", null)).thenReturn(null);
        when(n.getProperty("layer", null)).thenReturn(null);
        when(n.getProperty("lineStart", null)).thenReturn(null);
        when(n.getProperty("lineEnd", null)).thenReturn(null);
        when(n.getProperty("annotations", null)).thenReturn(null);
        return n;
    }

    /**
     * Wire up findById's transaction chain: first execute() returns the node row,
     * second execute() (the edge hydration) returns empty.
     */
    private void wireFindByIdResult(org.neo4j.graphdb.Node neo4jNode) {
        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);

        var nodeResult = mock(Result.class);
        when(nodeResult.hasNext()).thenReturn(true, false);
        when(nodeResult.next()).thenReturn(Map.of("n", neo4jNode));

        var edgeResult = mock(Result.class);
        when(edgeResult.hasNext()).thenReturn(false);

        when(tx.execute(anyString(), anyMap())).thenReturn(nodeResult, edgeResult);
    }
}
