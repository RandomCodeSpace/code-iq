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

import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for GraphStore methods not covered by GraphStoreTest, GraphStoreExtendedTest,
 * or GraphStoreAggregateStatsTest:
 * <ul>
 *   <li>getTopology()</li>
 *   <li>countEdges()</li>
 *   <li>countByFileExtension()</li>
 *   <li>countNodesByKind()</li>
 *   <li>countNodesByLayer()</li>
 *   <li>findEdgesPaginated()</li>
 *   <li>findEdgesByKindPaginated()</li>
 *   <li>getFilePathsWithCounts()</li>
 *   <li>countEdgesByKind()</li>
 *   <li>findNodesWithoutIncomingSemantic()</li>
 *   <li>findNodesWithoutIncoming() (deprecated)</li>
 *   <li>findEndpointNeighborsBatch()</li>
 *   <li>searchLexical()</li>
 *   <li>bulkSave() early-return for empty list</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphStoreTopologyAndStatsTest {

    @Mock
    private GraphRepository repository;

    @Mock
    private GraphDatabaseService graphDb;

    private GraphStore store;

    @BeforeEach
    void setUp() {
        store = new GraphStore(repository, graphDb);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Build a lightweight Result backed by a list of rows.
     * Returns a real (non-mock) implementation so it is safe to use inside
     * thenReturn() without triggering Mockito's "unfinished stubbing" check.
     */
    @SafeVarargs
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Result buildResult(Map<String, Object>... rows) {
        List<Map<String, Object>> list = Arrays.asList(rows);
        return new Result() {
            private final AtomicInteger idx = new AtomicInteger(0);
            @Override public boolean hasNext() { return idx.get() < list.size(); }
            @Override public Map<String, Object> next() { return list.get(idx.getAndIncrement()); }
            @Override public List<String> columns() { return list.isEmpty() ? List.of() : List.copyOf(list.getFirst().keySet()); }
            @Override public void close() {}
            @Override public <T> org.neo4j.graphdb.ResourceIterator<T> columnAs(String name) { throw new UnsupportedOperationException(); }
            @Override public org.neo4j.graphdb.QueryStatistics getQueryStatistics() { return null; }
            @Override public org.neo4j.graphdb.QueryExecutionType getQueryExecutionType() { return null; }
            @Override public org.neo4j.graphdb.ExecutionPlanDescription getExecutionPlanDescription() { return null; }
            @Override public String resultAsString() { return ""; }
            @Override public void writeAsStringTo(java.io.PrintWriter pw) {}
            @Override public void remove() {}
            @Override public Iterable<org.neo4j.graphdb.Notification> getNotifications() { return List.of(); }
            @Override public Iterable<org.neo4j.graphdb.GqlStatusObject> getGqlStatusObjects() { return List.of(); }
            @Override public <E extends Exception> void accept(org.neo4j.graphdb.Result.ResultVisitor<E> visitor) throws E {}
        };
    }

    /** Build an empty Result. */
    private static Result emptyResult() {
        return buildResult();
    }

    /**
     * Create a minimal mock Neo4j node with the mandatory properties that
     * nodeFromNeo4j() reads.  Extra properties (prop_*, annotations, lineStart,
     * lineEnd) default to null / empty-iterable.
     */
    private static org.neo4j.graphdb.Node mockNeo4jNode(String id, String kind, String label) {
        org.neo4j.graphdb.Node n = mock(org.neo4j.graphdb.Node.class);
        when(n.getProperty("id", null)).thenReturn(id);
        when(n.getProperty("kind", null)).thenReturn(kind);
        when(n.getProperty("label", "")).thenReturn(label);
        when(n.getProperty("fqn", null)).thenReturn(null);
        when(n.getProperty("module", null)).thenReturn(null);
        when(n.getProperty("filePath", null)).thenReturn(null);
        when(n.getProperty("layer", null)).thenReturn(null);
        when(n.getProperty("lineStart", null)).thenReturn(null);
        when(n.getProperty("lineEnd", null)).thenReturn(null);
        when(n.getProperty("annotations", null)).thenReturn(null);
        when(n.getPropertyKeys()).thenReturn(List.of());
        return n;
    }

    /**
     * Create a mock Neo4j node that also has prop_* keys, annotations, and
     * lineStart/lineEnd — exercises the property-restore path in nodeFromNeo4j().
     */
    private static org.neo4j.graphdb.Node mockRichNeo4jNode(String id, String kind, String label) {
        org.neo4j.graphdb.Node n = mockNeo4jNode(id, kind, label);
        when(n.getProperty("layer", null)).thenReturn("backend");
        when(n.getProperty("lineStart", null)).thenReturn(10);
        when(n.getProperty("lineEnd", null)).thenReturn(42);
        when(n.getProperty("annotations", null)).thenReturn("@Service,@Transactional");
        when(n.getPropertyKeys()).thenReturn(List.of("prop_language", "prop_framework"));
        when(n.getProperty("prop_language")).thenReturn("java");
        when(n.getProperty("prop_framework")).thenReturn("spring_boot");
        return n;
    }

    // -------------------------------------------------------------------------
    // bulkSave – empty list early return
    // -------------------------------------------------------------------------

    @Test
    void bulkSaveShouldReturnEarlyForEmptyList() {
        // If the list is empty, Neo4j must never be touched.
        store.bulkSave(List.of());

        verifyNoInteractions(graphDb);
    }

    // -------------------------------------------------------------------------
    // countEdges
    // -------------------------------------------------------------------------

    @Test
    void countEdgesShouldReturnCountFromNeo4j() {
        Transaction tx = mock(Transaction.class);
        Result result = buildResult(Map.of("cnt", 42L));
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(contains("RELATES_TO"))).thenReturn(result);

        assertEquals(42L, store.countEdges());
    }

    @Test
    void countEdgesShouldReturnZeroForEmptyGraph() {
        Transaction tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString())).thenReturn(emptyResult());

        assertEquals(0L, store.countEdges());
    }

    // -------------------------------------------------------------------------
    // countByFileExtension
    // -------------------------------------------------------------------------

    @Test
    void countByFileExtensionShouldReturnRows() {
        Transaction tx = mock(Transaction.class);
        Result result = buildResult(
                Map.of("ext", "java", "cnt", 50L),
                Map.of("ext", "ts", "cnt", 20L));
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString())).thenReturn(result);

        List<Map<String, Object>> rows = store.countByFileExtension();

        assertEquals(2, rows.size());
        assertEquals("java", rows.get(0).get("ext"));
        assertEquals(50L, rows.get(0).get("cnt"));
        assertEquals("ts", rows.get(1).get("ext"));
        assertEquals(20L, rows.get(1).get("cnt"));
    }

    @Test
    void countByFileExtensionShouldReturnEmptyListForEmptyGraph() {
        Transaction tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString())).thenReturn(emptyResult());

        List<Map<String, Object>> rows = store.countByFileExtension();

        assertTrue(rows.isEmpty());
    }

    // -------------------------------------------------------------------------
    // countNodesByKind
    // -------------------------------------------------------------------------

    @Test
    void countNodesByKindShouldReturnRows() {
        Transaction tx = mock(Transaction.class);
        Result result = buildResult(
                Map.of("kind", "class", "cnt", 100L),
                Map.of("kind", "method", "cnt", 500L));
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString())).thenReturn(result);

        List<Map<String, Object>> rows = store.countNodesByKind();

        assertEquals(2, rows.size());
        assertEquals("class", rows.get(0).get("kind"));
        assertEquals(100L, rows.get(0).get("cnt"));
        assertEquals("method", rows.get(1).get("kind"));
        assertEquals(500L, rows.get(1).get("cnt"));
    }

    @Test
    void countNodesByKindShouldReturnEmptyListForEmptyGraph() {
        Transaction tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString())).thenReturn(emptyResult());

        assertTrue(store.countNodesByKind().isEmpty());
    }

    // -------------------------------------------------------------------------
    // countNodesByLayer
    // -------------------------------------------------------------------------

    @Test
    void countNodesByLayerShouldReturnRows() {
        Transaction tx = mock(Transaction.class);
        Result result = buildResult(
                Map.of("layer", "backend", "cnt", 80L),
                Map.of("layer", "frontend", "cnt", 20L));
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString())).thenReturn(result);

        List<Map<String, Object>> rows = store.countNodesByLayer();

        assertEquals(2, rows.size());
        assertEquals("backend", rows.get(0).get("layer"));
        assertEquals(80L, rows.get(0).get("cnt"));
    }

    @Test
    void countNodesByLayerShouldReturnEmptyListForEmptyGraph() {
        Transaction tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString())).thenReturn(emptyResult());

        assertTrue(store.countNodesByLayer().isEmpty());
    }

    // -------------------------------------------------------------------------
    // findEdgesPaginated
    // -------------------------------------------------------------------------

    @Test
    void findEdgesPaginatedShouldReturnRows() {
        Transaction tx = mock(Transaction.class);
        Result result = buildResult(
                Map.of("id", "e1", "kind", "calls", "sourceId", "s1", "targetId", "t1"),
                Map.of("id", "e2", "kind", "imports", "sourceId", "s2", "targetId", "t2"));
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        List<Map<String, Object>> rows = store.findEdgesPaginated(0, 10);

        assertEquals(2, rows.size());
        assertEquals("e1", rows.get(0).get("id"));
        assertEquals("calls", rows.get(0).get("kind"));
        assertEquals("s1", rows.get(0).get("sourceId"));
        assertEquals("t1", rows.get(0).get("targetId"));
    }

    @Test
    void findEdgesPaginatedShouldReturnEmptyForEmptyGraph() {
        Transaction tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(emptyResult());

        assertTrue(store.findEdgesPaginated(0, 10).isEmpty());
    }

    // -------------------------------------------------------------------------
    // findEdgesByKindPaginated
    // -------------------------------------------------------------------------

    @Test
    void findEdgesByKindPaginatedShouldReturnMatchingRows() {
        Transaction tx = mock(Transaction.class);
        Result result = buildResult(
                Map.of("id", "e3", "kind", "calls", "sourceId", "sA", "targetId", "tB"));
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        List<Map<String, Object>> rows = store.findEdgesByKindPaginated("calls", 0, 5);

        assertEquals(1, rows.size());
        assertEquals("calls", rows.get(0).get("kind"));
        assertEquals("sA", rows.get(0).get("sourceId"));
    }

    @Test
    void findEdgesByKindPaginatedShouldReturnEmptyWhenNoneMatch() {
        Transaction tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(emptyResult());

        assertTrue(store.findEdgesByKindPaginated("depends_on", 0, 10).isEmpty());
    }

    // -------------------------------------------------------------------------
    // getFilePathsWithCounts
    // -------------------------------------------------------------------------

    @Test
    void getFilePathsWithCountsShouldReturnNonTruncatedResult() {
        Transaction tx = mock(Transaction.class);
        // maxFiles=3, return exactly 3 rows -> not truncated
        Result result = buildResult(
                Map.of("filePath", "src/A.java", "nodeCount", 2L),
                Map.of("filePath", "src/B.java", "nodeCount", 5L),
                Map.of("filePath", "src/C.java", "nodeCount", 1L));
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        GraphStore.FilePathResult fpResult = store.getFilePathsWithCounts(3);

        assertFalse(fpResult.truncated());
        assertEquals(3, fpResult.rows().size());
        assertEquals("src/A.java", fpResult.rows().get(0).get("filePath"));
        assertEquals(2L, fpResult.rows().get(0).get("nodeCount"));
    }

    @Test
    void getFilePathsWithCountsShouldReturnTruncatedResultWhenOverLimit() {
        Transaction tx = mock(Transaction.class);
        // maxFiles=2, query returns limit+1=3 rows -> truncated=true, only 2 rows returned
        Result result = buildResult(
                Map.of("filePath", "src/A.java", "nodeCount", 2L),
                Map.of("filePath", "src/B.java", "nodeCount", 5L),
                Map.of("filePath", "src/C.java", "nodeCount", 1L));
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        GraphStore.FilePathResult fpResult = store.getFilePathsWithCounts(2);

        assertTrue(fpResult.truncated());
        assertEquals(2, fpResult.rows().size());
        assertEquals("src/A.java", fpResult.rows().get(0).get("filePath"));
        assertEquals("src/B.java", fpResult.rows().get(1).get("filePath"));
    }

    @Test
    void getFilePathsWithCountsShouldReturnEmptyForEmptyGraph() {
        Transaction tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(emptyResult());

        GraphStore.FilePathResult fpResult = store.getFilePathsWithCounts(10);

        assertFalse(fpResult.truncated());
        assertTrue(fpResult.rows().isEmpty());
    }

    // -------------------------------------------------------------------------
    // countEdgesByKind
    // -------------------------------------------------------------------------

    @Test
    void countEdgesByKindShouldReturnCount() {
        Transaction tx = mock(Transaction.class);
        Result result = buildResult(Map.of("cnt", 7L));
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        assertEquals(7L, store.countEdgesByKind("calls"));
    }

    @Test
    void countEdgesByKindShouldReturnZeroWhenNoneExist() {
        Transaction tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(emptyResult());

        assertEquals(0L, store.countEdgesByKind("calls"));
    }

    // -------------------------------------------------------------------------
    // findNodesWithoutIncomingSemantic
    // -------------------------------------------------------------------------

    @Test
    void findNodesWithoutIncomingSemanticShouldReturnDeadCodeNodes() {
        Transaction tx = mock(Transaction.class);
        org.neo4j.graphdb.Node neo4jNode = mockNeo4jNode("node:Orphan", "class", "Orphan");
        Result result = buildResult(Map.of("n", neo4jNode));
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        List<CodeNode> nodes = store.findNodesWithoutIncomingSemantic(
                List.of("class", "method"),
                List.of("calls", "imports", "depends_on"),
                List.of("endpoint"),
                0, 10);

        assertEquals(1, nodes.size());
        assertEquals("node:Orphan", nodes.get(0).getId());
        assertEquals(NodeKind.CLASS, nodes.get(0).getKind());
    }

    @Test
    void findNodesWithoutIncomingSemanticShouldReturnEmptyWhenAllNodesHaveIncomingEdges() {
        Transaction tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(emptyResult());

        List<CodeNode> nodes = store.findNodesWithoutIncomingSemantic(
                List.of("class"),
                List.of("calls"),
                List.of("endpoint"),
                0, 10);

        assertTrue(nodes.isEmpty());
    }

    // -------------------------------------------------------------------------
    // findNodesWithoutIncoming (deprecated)
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("deprecation")
    void findNodesWithoutIncomingShouldReturnNodesWithNoIncomingEdgesAtAll() {
        Transaction tx = mock(Transaction.class);
        org.neo4j.graphdb.Node neo4jNode = mockNeo4jNode("node:Dead", "method", "deadMethod");
        Result result = buildResult(Map.of("n", neo4jNode));
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        List<CodeNode> nodes = store.findNodesWithoutIncoming(List.of("method"), 0, 10);

        assertEquals(1, nodes.size());
        assertEquals("node:Dead", nodes.get(0).getId());
    }

    @Test
    @SuppressWarnings("deprecation")
    void findNodesWithoutIncomingShouldReturnEmptyForEmptyGraph() {
        Transaction tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(emptyResult());

        assertTrue(store.findNodesWithoutIncoming(List.of("class"), 0, 10).isEmpty());
    }

    // -------------------------------------------------------------------------
    // findEndpointNeighborsBatch
    // -------------------------------------------------------------------------

    @Test
    void findEndpointNeighborsBatchShouldReturnEmptyForEmptyNodeIds() {
        Map<String, List<CodeNode>> result = store.findEndpointNeighborsBatch(List.of());

        assertTrue(result.isEmpty());
        verifyNoInteractions(graphDb);
    }

    @Test
    void findEndpointNeighborsBatchShouldReturnEndpointNeighbors() {
        Transaction tx = mock(Transaction.class);
        org.neo4j.graphdb.Node epNode = mockNeo4jNode("ep:GET:/api/users", "endpoint", "/api/users");
        Result result = buildResult(Map.of("matchId", "svc:UserService", "ep", epNode));

        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        Map<String, List<CodeNode>> neighbors = store.findEndpointNeighborsBatch(
                List.of("svc:UserService"));

        assertTrue(neighbors.containsKey("svc:UserService"));
        assertEquals(1, neighbors.get("svc:UserService").size());
        assertEquals("ep:GET:/api/users", neighbors.get("svc:UserService").get(0).getId());
    }

    @Test
    void findEndpointNeighborsBatchShouldHandleNonNodeRowValue() {
        Transaction tx = mock(Transaction.class);
        // ep column contains a non-Node value — should be skipped
        Result result = mock(Result.class);
        Iterator<Map<String, Object>> iter = List.<Map<String, Object>>of(
                Map.of("matchId", "svc:X", "ep", "not-a-node")).iterator();
        when(result.hasNext()).thenAnswer(inv -> iter.hasNext());
        when(result.next()).thenAnswer(inv -> iter.next());
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        Map<String, List<CodeNode>> neighbors = store.findEndpointNeighborsBatch(
                List.of("svc:X"));

        // The entry should not be added since the cast guard fails
        assertTrue(neighbors.isEmpty());
    }

    // -------------------------------------------------------------------------
    // searchLexical
    // -------------------------------------------------------------------------

    @Test
    void searchLexicalShouldReturnMatchingNodes() {
        Transaction tx = mock(Transaction.class);
        org.neo4j.graphdb.Node neo4jNode = mockNeo4jNode("cls:Foo", "class", "Foo");
        Result result = mock(Result.class);
        when(result.columns()).thenReturn(List.of("n"));
        Iterator<Map<String, Object>> iter = List.<Map<String, Object>>of(
                Map.of("n", neo4jNode)).iterator();
        when(result.hasNext()).thenAnswer(inv -> iter.hasNext());
        when(result.next()).thenAnswer(inv -> iter.next());
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        List<CodeNode> nodes = store.searchLexical("configuration", 5);

        assertEquals(1, nodes.size());
        assertEquals("cls:Foo", nodes.get(0).getId());
        assertEquals(NodeKind.CLASS, nodes.get(0).getKind());
    }

    @Test
    void searchLexicalShouldReturnEmptyForNoMatches() {
        Transaction tx = mock(Transaction.class);
        Result result = mock(Result.class);
        when(result.columns()).thenReturn(List.of("n"));
        when(result.hasNext()).thenReturn(false);
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        List<CodeNode> nodes = store.searchLexical("zzz_no_match", 5);

        assertTrue(nodes.isEmpty());
    }

    // -------------------------------------------------------------------------
    // getTopology
    // -------------------------------------------------------------------------

    @Test
    void getTopologyShouldReturnServicesInfrastructureAndConnections() {
        // getTopology() opens 3 separate transactions in sequence.
        Transaction txServices = mock(Transaction.class);
        Transaction txInfra = mock(Transaction.class);
        Transaction txConnections = mock(Transaction.class);

        Result servicesResult = buildResult(
                Map.of("id", "svc:orders", "label", "orders", "kind", "service",
                        "layer", "backend", "node_count", 12L));
        Result infraResult = buildResult(
                Map.of("id", "postgresql:orders-db", "label", "orders-db", "kind", "database_connection"));
        Result connectionsResult = buildResult(
                Map.of("source", "svc:orders", "target", "postgresql:orders-db",
                        "kind", "uses", "cnt", 3L));

        // First call → txServices, second → txInfra, third → txConnections
        when(graphDb.beginTx())
                .thenReturn(txServices)
                .thenReturn(txInfra)
                .thenReturn(txConnections);

        // Services query uses tx.execute(String) without params
        when(txServices.execute(anyString())).thenReturn(servicesResult);
        // Infra query uses tx.execute(String, Map)
        when(txInfra.execute(anyString(), anyMap())).thenReturn(infraResult);
        // Connections query uses tx.execute(String, Map)
        when(txConnections.execute(anyString(), anyMap())).thenReturn(connectionsResult);

        Map<String, Object> topology = store.getTopology();

        assertNotNull(topology);
        assertTrue(topology.containsKey("services"));
        assertTrue(topology.containsKey("infrastructure"));
        assertTrue(topology.containsKey("connections"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) topology.get("services");
        assertEquals(1, services.size());
        assertEquals("svc:orders", services.get(0).get("id"));
        assertEquals("orders", services.get(0).get("label"));
        assertEquals("service", services.get(0).get("kind"));
        assertEquals("backend", services.get(0).get("layer"));
        assertEquals(12L, services.get(0).get("node_count"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> infrastructure = (List<Map<String, Object>>) topology.get("infrastructure");
        assertEquals(1, infrastructure.size());
        assertEquals("postgresql:orders-db", infrastructure.get(0).get("id"));
        // type should be derived from id prefix
        assertEquals("postgresql", infrastructure.get(0).get("type"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> connections = (List<Map<String, Object>>) topology.get("connections");
        assertEquals(1, connections.size());
        assertEquals("svc:orders", connections.get(0).get("source"));
        assertEquals("postgresql:orders-db", connections.get(0).get("target"));
        assertEquals("uses", connections.get(0).get("kind"));
        assertEquals(3L, connections.get(0).get("count"));
    }

    @Test
    void getTopologyShouldReturnEmptyListsForEmptyGraph() {
        Transaction txServices = mock(Transaction.class);
        Transaction txInfra = mock(Transaction.class);
        Transaction txConnections = mock(Transaction.class);

        when(graphDb.beginTx())
                .thenReturn(txServices)
                .thenReturn(txInfra)
                .thenReturn(txConnections);
        when(txServices.execute(anyString())).thenReturn(emptyResult());
        when(txInfra.execute(anyString(), anyMap())).thenReturn(emptyResult());
        when(txConnections.execute(anyString(), anyMap())).thenReturn(emptyResult());

        Map<String, Object> topology = store.getTopology();

        @SuppressWarnings("unchecked")
        List<?> services = (List<?>) topology.get("services");
        @SuppressWarnings("unchecked")
        List<?> infrastructure = (List<?>) topology.get("infrastructure");
        @SuppressWarnings("unchecked")
        List<?> connections = (List<?>) topology.get("connections");

        assertTrue(services.isEmpty());
        assertTrue(infrastructure.isEmpty());
        assertTrue(connections.isEmpty());
    }

    @Test
    void getTopologyInfraTypeShouldFallbackToKindWhenIdHasNoColon() {
        Transaction txServices = mock(Transaction.class);
        Transaction txInfra = mock(Transaction.class);
        Transaction txConnections = mock(Transaction.class);

        // Infrastructure id without a colon → type should fall back to kind
        Result infraResult = buildResult(
                Map.of("id", "my-topic", "label", "my-topic", "kind", "topic"));

        when(graphDb.beginTx())
                .thenReturn(txServices)
                .thenReturn(txInfra)
                .thenReturn(txConnections);
        when(txServices.execute(anyString())).thenReturn(emptyResult());
        when(txInfra.execute(anyString(), anyMap())).thenReturn(infraResult);
        when(txConnections.execute(anyString(), anyMap())).thenReturn(emptyResult());

        Map<String, Object> topology = store.getTopology();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> infrastructure = (List<Map<String, Object>>) topology.get("infrastructure");
        assertEquals(1, infrastructure.size());
        assertEquals("topic", infrastructure.get(0).get("type"));
    }

    @Test
    void getTopologyServiceWithNullNodeCountShouldDefaultToZero() {
        Transaction txServices = mock(Transaction.class);
        Transaction txInfra = mock(Transaction.class);
        Transaction txConnections = mock(Transaction.class);

        // node_count is null (e.g. no optional match returned a value)
        Map<String, Object> svcRow = new java.util.LinkedHashMap<>();
        svcRow.put("id", "svc:empty");
        svcRow.put("label", "empty");
        svcRow.put("kind", "service");
        svcRow.put("layer", "backend");
        svcRow.put("node_count", null);

        Result servicesResult = buildResult(svcRow);

        when(graphDb.beginTx())
                .thenReturn(txServices)
                .thenReturn(txInfra)
                .thenReturn(txConnections);
        when(txServices.execute(anyString())).thenReturn(servicesResult);
        when(txInfra.execute(anyString(), anyMap())).thenReturn(emptyResult());
        when(txConnections.execute(anyString(), anyMap())).thenReturn(emptyResult());

        Map<String, Object> topology = store.getTopology();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) topology.get("services");
        assertEquals(1, services.size());
        assertEquals(0L, services.get(0).get("node_count"));
    }

    // -------------------------------------------------------------------------
    // nodeFromNeo4j - exercised indirectly via findNodesWithoutIncomingSemantic
    // to verify prop_*, annotations, lineStart, lineEnd restore paths
    // -------------------------------------------------------------------------

    @Test
    void nodeFromNeo4jShouldRestorePropertiesAnnotationsAndLineNumbers() {
        Transaction tx = mock(Transaction.class);
        org.neo4j.graphdb.Node richNode = mockRichNeo4jNode("cls:Rich", "class", "Rich");

        Result result = mock(Result.class);
        when(result.columns()).thenReturn(List.of("n"));
        Iterator<Map<String, Object>> iter = List.<Map<String, Object>>of(
                Map.of("n", richNode)).iterator();
        when(result.hasNext()).thenAnswer(inv -> iter.hasNext());
        when(result.next()).thenAnswer(inv -> iter.next());
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString(), anyMap())).thenReturn(result);

        List<CodeNode> nodes = store.findNodesWithoutIncomingSemantic(
                List.of("class"), List.of("calls"), List.of(), 0, 10);

        assertEquals(1, nodes.size());
        CodeNode node = nodes.get(0);
        assertEquals("backend", node.getLayer());
        assertEquals(10, node.getLineStart());
        assertEquals(42, node.getLineEnd());
        assertNotNull(node.getAnnotations());
        assertTrue(node.getAnnotations().contains("@Service"));
        assertTrue(node.getAnnotations().contains("@Transactional"));
        assertEquals("java", node.getProperties().get("language"));
        assertEquals("spring_boot", node.getProperties().get("framework"));
    }

    // -------------------------------------------------------------------------
    // computeAggregateCategoryStats – default case returns null
    // -------------------------------------------------------------------------

    @Test
    void computeAggregateCategoryStatsShouldReturnNullForUnknownCategory() {
        assertNull(store.computeAggregateCategoryStats("unknown"));
        assertNull(store.computeAggregateCategoryStats("nonexistent_category"));
    }
}
