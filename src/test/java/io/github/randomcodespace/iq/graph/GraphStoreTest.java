package io.github.randomcodespace.iq.graph;

import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphStoreTest {

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
        return neo4jNode;
    }

    /**
     * Sets up graphDb to return a single-node result for the given column name.
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
    void shouldSaveNode() {
        var node = new CodeNode("mod:app.py:module:app", NodeKind.MODULE, "app");
        when(repository.save(node)).thenReturn(node);

        var saved = store.save(node);

        assertEquals(node, saved);
        verify(repository).save(node);
    }

    @Test
    void shouldDeleteAll() {
        store.deleteAll();

        verify(repository).deleteAll();
    }

    // --- Read operations (use embedded Neo4j API) ---

    @Test
    void shouldFindById() {
        mockNodeResult("mod:app.py:module:app", "module", "app", "n");

        var result = store.findById("mod:app.py:module:app");

        assertTrue(result.isPresent());
        assertEquals("mod:app.py:module:app", result.get().getId());
        assertEquals("app", result.get().getLabel());
    }

    @Test
    void shouldReturnEmptyForMissingId() {
        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        var result = mock(Result.class);
        when(result.hasNext()).thenReturn(false);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        var found = store.findById("nonexistent");

        assertTrue(found.isEmpty());
    }

    @Test
    void shouldFindByKind() {
        mockNodeResult("ep:routes.py:endpoint:get_users", "endpoint", "get_users", "n");

        var results = store.findByKind(NodeKind.ENDPOINT);

        assertEquals(1, results.size());
        assertEquals("get_users", results.getFirst().getLabel());
    }

    @Test
    void shouldCount() {
        var tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        var result = mock(Result.class);
        when(result.hasNext()).thenReturn(true, false);
        when(result.next()).thenReturn(Map.of("cnt", 42L));
        when(tx.execute(anyString())).thenReturn(result);

        assertEquals(42L, store.count());
    }

    @Test
    void shouldSearch() {
        mockNodeResult("cls:models.py:class:User", "class", "User", "n");

        var results = store.search("User");

        assertEquals(1, results.size());
        assertEquals("User", results.getFirst().getLabel());
    }
}
