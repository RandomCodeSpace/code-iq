package io.github.randomcodespace.iq.graph;

import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
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
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GraphStoreExtendedTest {

    @Mock
    private GraphRepository repository;

    @Mock
    private GraphDatabaseService graphDb;

    private GraphStore store;

    @BeforeEach
    void setUp() {
        store = new GraphStore(repository, graphDb);
    }

    // --- Helper methods ---

    /**
     * Creates a mock Neo4j Node with the given id, kind, and label.
     */
    private org.neo4j.graphdb.Node mockNeo4jNode(String id, String kind, String label) {
        var neo4jNode = mock(org.neo4j.graphdb.Node.class);
        when(neo4jNode.getProperty("id", null)).thenReturn(id);
        when(neo4jNode.getProperty("kind", null)).thenReturn(kind);
        when(neo4jNode.getProperty("label", "")).thenReturn(label);
        when(neo4jNode.getProperty("fqn", null)).thenReturn(null);
        when(neo4jNode.getProperty("module", null)).thenReturn(null);
        when(neo4jNode.getProperty("filePath", null)).thenReturn(null);
        when(neo4jNode.getProperty("layer", null)).thenReturn(null);
        when(neo4jNode.getProperty("lineStart", null)).thenReturn(null);
        when(neo4jNode.getProperty("lineEnd", null)).thenReturn(null);
        when(neo4jNode.getProperty("annotations", null)).thenReturn(null);
        when(neo4jNode.getProperty("confidence", null)).thenReturn(null);
        when(neo4jNode.getProperty("source", null)).thenReturn(null);
        return neo4jNode;
    }

    /**
     * Sets up graphDb to return a single-node result for the given column name.
     * Returns the mock Transaction for further verification if needed.
     */
    private Transaction mockNodeResult(String id, String kind, String label, String column) {
        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        var neo4jNode = mockNeo4jNode(id, kind, label);
        var result = mock(Result.class);
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn(Map.of(column, neo4jNode));
        when(result.columns()).thenReturn(List.of(column));
        when(tx.execute(anyString(), anyMap())).thenReturn(result);
        return tx;
    }

    // --- Write operations (still use repository) ---

    @Test
    void shouldSaveAll() {
        var nodes = List.of(
                new CodeNode("n1", NodeKind.CLASS, "A"),
                new CodeNode("n2", NodeKind.CLASS, "B")
        );
        when(repository.saveAll(nodes)).thenReturn(nodes);

        var saved = store.saveAll(nodes);
        assertEquals(2, saved.size());
        verify(repository).saveAll(nodes);
    }

    @Test
    void shouldDeleteById() {
        store.deleteById("n1");
        verify(repository).deleteById("n1");
    }

    // --- Read operations (use embedded Neo4j API) ---

    @Test
    void shouldFindAll() {
        // findAll() opens two transactions: queryNodes + hydrateEdges
        var tx1 = mock(Transaction.class);
        var tx2 = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx1, tx2);

        // First tx: queryNodes returns one node
        var neo4jNode = mockNeo4jNode("n1", "class", "A");
        var nodeResult = mock(Result.class);
        when(nodeResult.hasNext()).thenReturn(true, false);
        when(nodeResult.next()).thenReturn(Map.of("n", neo4jNode));
        when(nodeResult.columns()).thenReturn(List.of("n"));
        when(tx1.execute(anyString(), anyMap())).thenReturn(nodeResult);

        // Second tx: hydrateEdges returns no edges
        var edgeResult = mock(Result.class);
        when(edgeResult.hasNext()).thenReturn(false);
        when(tx2.execute(anyString())).thenReturn(edgeResult);

        var result = store.findAll();
        assertEquals(1, result.size());
        assertEquals("A", result.getFirst().getLabel());
    }

    @Test
    void shouldFindByLayer() {
        mockNodeResult("n1", "class", "A", "n");

        var results = store.findByLayer("backend");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindByModule() {
        mockNodeResult("n1", "module", "core", "n");

        var results = store.findByModule("core");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindByFilePath() {
        mockNodeResult("n1", "class", "A", "n");

        var results = store.findByFilePath("src/Main.java");
        assertEquals(1, results.size());
    }

    @Test
    void shouldSearchWithLimit() {
        mockNodeResult("n1", "class", "User", "n");

        var results = store.search("User", 10);
        assertEquals(1, results.size());
        assertEquals("User", results.getFirst().getLabel());
    }

    @Test
    void shouldFindNeighbors() {
        // findNeighbors returns column 'm'
        mockNodeResult("n2", "class", "B", "m");

        var results = store.findNeighbors("n1");
        assertEquals(1, results.size());
        assertEquals("B", results.getFirst().getLabel());
    }

    @Test
    void shouldFindOutgoingNeighbors() {
        // findOutgoingNeighbors returns column 'm'
        mockNodeResult("n2", "class", "B", "m");

        var results = store.findOutgoingNeighbors("n1");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindIncomingNeighbors() {
        // findIncomingNeighbors returns column 'm'
        mockNodeResult("n0", "class", "A", "m");

        var results = store.findIncomingNeighbors("n1");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindShortestPath() {
        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        var result = mock(Result.class);
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn(Map.of("ids", List.of("A", "B", "C")));
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        var path = store.findShortestPath("A", "C");
        assertEquals(3, path.size());
        assertEquals("A", path.get(0));
        assertEquals("C", path.get(2));
    }

    @Test
    void shouldReturnEmptyPathWhenNotFound() {
        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        var result = mock(Result.class);
        when(result.hasNext()).thenReturn(false);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        var path = store.findShortestPath("A", "Z");
        assertTrue(path.isEmpty());
    }

    @Test
    void shouldFindEgoGraph() {
        // findEgoGraph returns column 'b' (DISTINCT b in Cypher)
        mockNodeResult("n1", "class", "A", "b");

        var result = store.findEgoGraph("center", 2);
        assertEquals(1, result.size());
    }

    @Test
    void shouldTraceImpact() {
        // traceImpact returns column 'b' (DISTINCT b in Cypher)
        mockNodeResult("n2", "class", "B", "b");

        var result = store.traceImpact("n1", 3);
        assertEquals(1, result.size());
    }

    @Test
    void shouldFindCycles() {
        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        var result = mock(Result.class);
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn(Map.of("ids", List.of("A", "B", "A")));
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        var cycles = store.findCycles(10);
        assertEquals(1, cycles.size());
        assertEquals(3, cycles.getFirst().size());
    }

    @Test
    void shouldReturnEmptyCyclesWhenNoneFound() {
        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        var result = mock(Result.class);
        when(result.hasNext()).thenReturn(false);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        var cycles = store.findCycles(10);
        assertTrue(cycles.isEmpty());
    }

    @Test
    void shouldFindConsumers() {
        // findConsumers returns column 'm'
        mockNodeResult("c1", "class", "Consumer", "m");

        var results = store.findConsumers("topic");
        assertEquals(1, results.size());
        assertEquals("Consumer", results.getFirst().getLabel());
    }

    @Test
    void shouldFindProducers() {
        // findProducers returns column 'm'
        mockNodeResult("p1", "class", "Producer", "m");

        var results = store.findProducers("topic");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindCallers() {
        // findCallers returns column 'm'
        mockNodeResult("caller1", "method", "doWork", "m");

        var results = store.findCallers("target");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindDependencies() {
        // findDependencies returns column 'm'
        mockNodeResult("dep1", "module", "lib", "m");

        var results = store.findDependencies("mod");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindDependents() {
        // findDependents returns column 'm'
        mockNodeResult("dep1", "module", "app", "m");

        var results = store.findDependents("lib");
        assertEquals(1, results.size());
    }

    @Test
    void shouldFindByKindPaginated() {
        Transaction tx = mockNodeResult("n1", "class", "A", "n");

        var results = store.findByKindPaginated("class", 0, 10);
        assertEquals(1, results.size());
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(tx).execute(query.capture(), anyMap());
        assertTrue(query.getValue().contains("ORDER BY n.id"));
    }

    @Test
    void shouldFindAllPaginated() {
        Transaction tx = mockNodeResult("n1", "class", "A", "n");

        var results = store.findAllPaginated(0, 10);
        assertEquals(1, results.size());
        ArgumentCaptor<String> query = ArgumentCaptor.forClass(String.class);
        verify(tx).execute(query.capture(), anyMap());
        assertTrue(query.getValue().contains("ORDER BY n.id"));
    }

    @Test
    void shouldCountByKind() {
        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        var result = mock(Result.class);
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn(Map.of("cnt", 15L));
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        assertEquals(15L, store.countByKind("class"));
    }
}
