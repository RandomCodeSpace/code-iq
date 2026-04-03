package io.github.randomcodespace.iq.query;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryServiceTest {

    @Mock
    private GraphStore graphStore;

    private CodeIqConfig config;
    private StatsService statsService;
    private QueryService service;

    @BeforeEach
    void setUp() {
        config = new CodeIqConfig();
        config.setMaxDepth(10);
        config.setMaxRadius(10);
        service = new QueryService(graphStore, config);
    }

    private CodeNode makeNode(String id, NodeKind kind, String label) {
        var node = new CodeNode(id, kind, label);
        node.setLayer("backend");
        node.setModule("app");
        node.setFilePath("src/app.py");
        return node;
    }

    private CodeNode makeNodeWithEdge(String id, NodeKind kind, String label,
                                       String targetId, EdgeKind edgeKind) {
        var node = makeNode(id, kind, label);
        var target = makeNode(targetId, NodeKind.CLASS, "Target");
        var edge = new CodeEdge("edge:" + id + ":" + targetId, edgeKind, id, target);
        node.setEdges(new ArrayList<>(List.of(edge)));
        return node;
    }

    // --- getStats ---

    @Test
    void getStatsShouldReturnNodeAndEdgeCounts() {
        // Mock Cypher aggregation from GraphStore
        Map<String, Object> aggregateStats = new java.util.LinkedHashMap<>();
        aggregateStats.put("graph", Map.of("nodes", 2L, "edges", 1L, "files", 2L));
        aggregateStats.put("languages", Map.of("java", 2L));
        aggregateStats.put("frameworks", Map.of());
        aggregateStats.put("infra", Map.of("databases", Map.of(), "messaging", Map.of(), "cloud", Map.of()));
        Map<String, Object> rest = new java.util.LinkedHashMap<>();
        rest.put("total", 1L);
        rest.put("by_method", Map.of("GET", 1L));
        Map<String, Object> connections = new java.util.LinkedHashMap<>();
        connections.put("rest", rest);
        connections.put("grpc", 0L);
        connections.put("websocket", 0L);
        connections.put("producers", 0L);
        connections.put("consumers", 0L);
        aggregateStats.put("connections", connections);
        aggregateStats.put("auth", Map.of());
        aggregateStats.put("architecture", Map.of("classes", 1L));

        when(graphStore.computeAggregateStats()).thenReturn(aggregateStats);
        when(graphStore.countNodesByKind()).thenReturn(List.of(
                Map.of("kind", "endpoint", "cnt", 1L),
                Map.of("kind", "class", "cnt", 1L)));
        when(graphStore.countNodesByLayer()).thenReturn(List.of(
                Map.of("layer", "backend", "cnt", 2L)));

        Map<String, Object> stats = service.getStats();

        @SuppressWarnings("unchecked")
        Map<String, Object> graph = (Map<String, Object>) stats.get("graph");
        assertEquals(2L, graph.get("nodes"));
        assertEquals(1L, graph.get("edges"));
        assertEquals(2L, graph.get("files"));
        assertNotNull(stats.get("languages"));
        assertNotNull(stats.get("frameworks"));
        assertNotNull(stats.get("infra"));
        assertNotNull(stats.get("connections"));
        assertNotNull(stats.get("auth"));
        assertNotNull(stats.get("architecture"));
        @SuppressWarnings("unchecked")
        Map<String, Object> resultConnections = (Map<String, Object>) stats.get("connections");
        @SuppressWarnings("unchecked")
        Map<String, Object> resultRest = (Map<String, Object>) resultConnections.get("rest");
        assertEquals(1L, resultRest.get("total"));
        assertEquals(2L, stats.get("node_count"));
        assertEquals(1L, stats.get("edge_count"));
        assertNotNull(stats.get("nodes_by_kind"));
        assertNotNull(stats.get("nodes_by_layer"));
    }

    // --- listKinds ---

    @Test
    void listKindsShouldReturnKindCounts() {
        when(graphStore.countNodesByKind()).thenReturn(List.of(
                Map.of("kind", "endpoint", "cnt", 2L),
                Map.of("kind", "class", "cnt", 1L)));

        Map<String, Object> result = service.listKinds();

        assertNotNull(result.get("kinds"));
        assertEquals(3L, result.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> kinds = (List<Map<String, Object>>) result.get("kinds");
        // endpoint has 2 nodes, should be first (sorted by count desc)
        assertEquals("endpoint", kinds.getFirst().get("kind"));
        assertEquals(2L, kinds.getFirst().get("count"));
    }

    // --- nodesByKind ---

    @Test
    void nodesByKindShouldReturnPaginated() {
        var n1 = makeNode("n1", NodeKind.ENDPOINT, "getUsers");
        when(graphStore.findByKindPaginated("endpoint", 0, 50)).thenReturn(List.of(n1));
        when(graphStore.countByKind("endpoint")).thenReturn(1L);

        Map<String, Object> result = service.nodesByKind("endpoint", 50, 0);

        assertEquals("endpoint", result.get("kind"));
        assertEquals(1L, result.get("total"));
        assertEquals(0, result.get("offset"));
        assertEquals(50, result.get("limit"));
    }

    // --- listNodes ---

    @Test
    void listNodesShouldFilterByKind() {
        var n1 = makeNode("n1", NodeKind.ENDPOINT, "getUsers");
        when(graphStore.findByKindPaginated("endpoint", 0, 100)).thenReturn(List.of(n1));

        Map<String, Object> result = service.listNodes("endpoint", 100, 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) result.get("nodes");
        assertEquals(1, nodes.size());
    }

    @Test
    void listNodesShouldReturnAllWhenNoKind() {
        var n1 = makeNode("n1", NodeKind.ENDPOINT, "getUsers");
        when(graphStore.findAllPaginated(0, 100)).thenReturn(List.of(n1));

        Map<String, Object> result = service.listNodes(null, 100, 0);

        assertEquals(1, result.get("count"));
    }

    // --- listEdges ---

    @Test
    void listEdgesShouldFilterByKind() {
        when(graphStore.findEdgesByKindPaginated("calls", 0, 100)).thenReturn(List.of(
                Map.of("id", "e1", "kind", "calls", "sourceId", "n1", "targetId", "n2")));
        when(graphStore.countEdgesByKind("calls")).thenReturn(1L);

        Map<String, Object> result = service.listEdges("calls", 100, 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) result.get("edges");
        assertEquals(1, edges.size());
    }

    @Test
    void listEdgesShouldExcludeNonMatchingKind() {
        when(graphStore.findEdgesByKindPaginated("imports", 0, 100)).thenReturn(List.of());
        when(graphStore.countEdgesByKind("imports")).thenReturn(0L);

        Map<String, Object> result = service.listEdges("imports", 100, 0);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> edges = (List<Map<String, Object>>) result.get("edges");
        assertEquals(0, edges.size());
    }

    // --- nodeDetailWithEdges ---

    @Test
    void nodeDetailShouldReturnDetailWithEdges() {
        var n1 = makeNodeWithEdge("n1", NodeKind.ENDPOINT, "getUsers",
                "n2", EdgeKind.CALLS);
        when(graphStore.findById("n1")).thenReturn(Optional.of(n1));
        when(graphStore.findIncomingNeighbors("n1")).thenReturn(List.of());

        Map<String, Object> result = service.nodeDetailWithEdges("n1");

        assertNotNull(result);
        assertEquals("n1", result.get("id"));
        assertNotNull(result.get("outgoing_edges"));
        assertNotNull(result.get("incoming_nodes"));
    }

    @Test
    void nodeDetailShouldReturnNullForMissing() {
        when(graphStore.findById("nonexistent")).thenReturn(Optional.empty());

        assertNull(service.nodeDetailWithEdges("nonexistent"));
    }

    // --- getNeighbors ---

    @Test
    void getNeighborsShouldUseBothDirection() {
        var n2 = makeNode("n2", NodeKind.CLASS, "UserService");
        when(graphStore.findNeighbors("n1")).thenReturn(List.of(n2));

        Map<String, Object> result = service.getNeighbors("n1", "both");

        assertEquals("both", result.get("direction"));
        assertEquals(1, result.get("count"));
    }

    @Test
    void getNeighborsShouldUseOutDirection() {
        when(graphStore.findOutgoingNeighbors("n1")).thenReturn(List.of());

        Map<String, Object> result = service.getNeighbors("n1", "out");

        assertEquals("out", result.get("direction"));
        verify(graphStore).findOutgoingNeighbors("n1");
    }

    @Test
    void getNeighborsShouldUseInDirection() {
        when(graphStore.findIncomingNeighbors("n1")).thenReturn(List.of());

        Map<String, Object> result = service.getNeighbors("n1", "in");

        assertEquals("in", result.get("direction"));
        verify(graphStore).findIncomingNeighbors("n1");
    }

    // --- shortestPath ---

    @Test
    void shortestPathShouldReturnPath() {
        when(graphStore.findShortestPath("a", "b")).thenReturn(List.of("a", "c", "b"));

        Map<String, Object> result = service.shortestPath("a", "b");

        assertNotNull(result);
        assertEquals("a", result.get("source"));
        assertEquals("b", result.get("target"));
        assertEquals(2, result.get("length"));
    }

    @Test
    void shortestPathShouldReturnNullWhenNoPath() {
        when(graphStore.findShortestPath("a", "b")).thenReturn(List.of());

        assertNull(service.shortestPath("a", "b"));
    }

    // --- findCycles ---

    @Test
    void findCyclesShouldReturnCycles() {
        List<List<String>> cycles = List.of(List.of("a", "b", "a"));
        when(graphStore.findCycles(100)).thenReturn(cycles);

        Map<String, Object> result = service.findCycles(100);

        assertEquals(1, result.get("count"));
    }

    // --- traceImpact ---

    @Test
    void traceImpactShouldCapDepth() {
        config.setMaxDepth(5);
        var impacted = makeNode("n2", NodeKind.CLASS, "Service");
        when(graphStore.traceImpact("n1", 5)).thenReturn(List.of(impacted));

        Map<String, Object> result = service.traceImpact("n1", 20);

        assertEquals(5, result.get("depth"));
        verify(graphStore).traceImpact("n1", 5);
    }

    // --- egoGraph ---

    @Test
    void egoGraphShouldCapRadius() {
        config.setMaxRadius(5);
        when(graphStore.findEgoGraph("center", 5)).thenReturn(new ArrayList<>());
        var centerNode = makeNode("center", NodeKind.MODULE, "app");
        when(graphStore.findById("center")).thenReturn(Optional.of(centerNode));

        Map<String, Object> result = service.egoGraph("center", 20);

        assertEquals(5, result.get("radius"));
        verify(graphStore).findEgoGraph("center", 5);
    }

    // --- consumersOf ---

    @Test
    void consumersOfShouldReturnConsumers() {
        var consumer = makeNode("c1", NodeKind.METHOD, "handleMessage");
        when(graphStore.findConsumers("topic1")).thenReturn(List.of(consumer));

        Map<String, Object> result = service.consumersOf("topic1");

        assertEquals("topic1", result.get("target"));
        assertEquals(1, result.get("count"));
    }

    // --- producersOf ---

    @Test
    void producersOfShouldReturnProducers() {
        when(graphStore.findProducers("topic1")).thenReturn(List.of());

        Map<String, Object> result = service.producersOf("topic1");

        assertEquals(0, result.get("count"));
    }

    // --- callersOf ---

    @Test
    void callersOfShouldReturnCallers() {
        when(graphStore.findCallers("fn1")).thenReturn(List.of());

        Map<String, Object> result = service.callersOf("fn1");

        assertEquals("fn1", result.get("target"));
    }

    // --- dependenciesOf ---

    @Test
    void dependenciesOfShouldReturnDeps() {
        when(graphStore.findDependencies("mod1")).thenReturn(List.of());

        Map<String, Object> result = service.dependenciesOf("mod1");

        assertEquals("mod1", result.get("module"));
    }

    // --- dependentsOf ---

    @Test
    void dependentsOfShouldReturnDeps() {
        when(graphStore.findDependents("mod1")).thenReturn(List.of());

        Map<String, Object> result = service.dependentsOf("mod1");

        assertEquals("mod1", result.get("module"));
    }

    // --- findComponentByFile ---

    @Test
    void findComponentByFileShouldReturnFileNodes() {
        var n1 = makeNode("n1", NodeKind.MODULE, "app");
        when(graphStore.findByFilePath("src/app.py")).thenReturn(List.of(n1));

        Map<String, Object> result = service.findComponentByFile("src/app.py");

        assertEquals("src/app.py", result.get("file"));
        assertEquals(1, result.get("count"));
        assertEquals("app", result.get("module"));
        assertEquals("backend", result.get("layer"));
    }

    @Test
    void findComponentByFileShouldHandleNoResults() {
        when(graphStore.findByFilePath("unknown.py")).thenReturn(List.of());

        Map<String, Object> result = service.findComponentByFile("unknown.py");

        assertEquals(0, result.get("count"));
        assertNull(result.get("module"));
    }

    // --- searchGraph ---

    @Test
    void searchGraphShouldReturnResults() {
        var n1 = makeNode("n1", NodeKind.CLASS, "UserService");
        when(graphStore.search("User", 50)).thenReturn(List.of(n1));

        List<Map<String, Object>> results = service.searchGraph("User", 50);

        assertEquals(1, results.size());
        assertEquals("UserService", results.getFirst().get("label"));
    }

    @Test
    void searchGraphShouldCapLimit() {
        when(graphStore.search("test", 200)).thenReturn(List.of());

        service.searchGraph("test", 500);

        verify(graphStore).search("test", 200);
    }

    // --- findDeadCode ---

    @Test
    void findDeadCodeShouldReturnNodesWithoutSemanticIncoming() {
        var deadClass = makeNode("cls:dead", NodeKind.CLASS, "UnusedHelper");
        when(graphStore.findNodesWithoutIncomingSemantic(anyList(), anyList(), anyList(), eq(0), eq(100)))
                .thenReturn(List.of(deadClass));

        Map<String, Object> result = service.findDeadCode(null, 100);

        assertEquals(1, result.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deadCode = (List<Map<String, Object>>) result.get("dead_code");
        assertEquals("cls:dead", deadCode.getFirst().get("id"));
        assertEquals("class", deadCode.getFirst().get("kind"));
        assertEquals("UnusedHelper", deadCode.getFirst().get("label"));
    }

    @Test
    void findDeadCodeShouldPassSemanticEdgeKinds() {
        when(graphStore.findNodesWithoutIncomingSemantic(anyList(), anyList(), anyList(), eq(0), eq(50)))
                .thenReturn(List.of());

        service.findDeadCode(null, 50);

        // Verify semantic edge kinds are passed (not structural ones like contains, defines)
        @SuppressWarnings("unchecked")
        var captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(graphStore).findNodesWithoutIncomingSemantic(anyList(), captor.capture(), anyList(), eq(0), eq(50));
        @SuppressWarnings("unchecked")
        List<String> semanticKinds = captor.getValue();
        assertTrue(semanticKinds.contains("calls"), "Should include 'calls'");
        assertTrue(semanticKinds.contains("imports"), "Should include 'imports'");
        assertTrue(semanticKinds.contains("depends_on"), "Should include 'depends_on'");
        assertTrue(semanticKinds.contains("extends"), "Should include 'extends'");
        assertTrue(semanticKinds.contains("implements"), "Should include 'implements'");
        assertFalse(semanticKinds.contains("contains"), "Should NOT include 'contains'");
        assertFalse(semanticKinds.contains("defines"), "Should NOT include 'defines'");
    }

    @Test
    void findDeadCodeShouldExcludeEntryPointKinds() {
        when(graphStore.findNodesWithoutIncomingSemantic(anyList(), anyList(), anyList(), eq(0), eq(50)))
                .thenReturn(List.of());

        service.findDeadCode(null, 50);

        // Verify entry point kinds are excluded
        @SuppressWarnings("unchecked")
        var kindCaptor = org.mockito.ArgumentCaptor.forClass(List.class);
        // args: kinds, semanticEdgeKinds, excludeNodeKinds, offset, limit
        verify(graphStore).findNodesWithoutIncomingSemantic(anyList(), anyList(), kindCaptor.capture(), eq(0), eq(50));
        @SuppressWarnings("unchecked")
        List<String> excludeKinds = kindCaptor.getValue();
        assertTrue(excludeKinds.contains("endpoint"), "Should exclude endpoints");
        assertTrue(excludeKinds.contains("websocket_endpoint"), "Should exclude websocket endpoints");
        assertTrue(excludeKinds.contains("migration"), "Should exclude migrations");
        assertTrue(excludeKinds.contains("config_file"), "Should exclude config files");
    }

    @Test
    void findDeadCodeShouldFilterBySpecificKind() {
        when(graphStore.findNodesWithoutIncomingSemantic(eq(List.of("method")), anyList(), anyList(), eq(0), eq(50)))
                .thenReturn(List.of());

        service.findDeadCode("method", 50);

        verify(graphStore).findNodesWithoutIncomingSemantic(eq(List.of("method")), anyList(), anyList(), eq(0), eq(50));
    }

    @Test
    void findDeadCodeShouldReturnEmptyWhenAllNodesHaveSemanticEdges() {
        when(graphStore.findNodesWithoutIncomingSemantic(anyList(), anyList(), anyList(), eq(0), eq(100)))
                .thenReturn(List.of());

        Map<String, Object> result = service.findDeadCode(null, 100);

        assertEquals(0, result.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> deadCode = (List<Map<String, Object>>) result.get("dead_code");
        assertTrue(deadCode.isEmpty());
    }

    // --- findRelatedEndpoints ---

    @Test
    void findRelatedEndpointsShouldUsesBatchQueryInsteadOfNPlusOne() {
        var classNode = makeNode("cls:UserService", NodeKind.CLASS, "UserService");
        var endpointNode = makeNode("ep:getUsers", NodeKind.ENDPOINT, "getUsers");
        when(graphStore.search("UserService", 50)).thenReturn(List.of(classNode));
        when(graphStore.findEndpointNeighborsBatch(List.of("cls:UserService")))
                .thenReturn(Map.of("cls:UserService", List.of(endpointNode)));

        Map<String, Object> result = service.findRelatedEndpoints("UserService");

        assertEquals("UserService", result.get("identifier"));
        assertEquals(1, result.get("count"));
        assertEquals(1, result.get("searched_nodes"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) result.get("endpoints");
        assertEquals("ep:getUsers", endpoints.getFirst().get("id"));
        assertEquals("cls:UserService", endpoints.getFirst().get("connected_via"));
        // Verify no per-node findNeighbors calls were made
        verify(graphStore, never()).findNeighbors(anyString());
    }

    @Test
    void findRelatedEndpointsShouldIncludeDirectEndpointMatches() {
        var endpointNode = makeNode("ep:getUsers", NodeKind.ENDPOINT, "getUsers");
        when(graphStore.search("getUsers", 50)).thenReturn(List.of(endpointNode));
        when(graphStore.findEndpointNeighborsBatch(List.of("ep:getUsers"))).thenReturn(Map.of());

        Map<String, Object> result = service.findRelatedEndpoints("getUsers");

        assertEquals(1, result.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> endpoints = (List<Map<String, Object>>) result.get("endpoints");
        assertEquals("ep:getUsers", endpoints.getFirst().get("id"));
        // Direct endpoint matches have no connected_via
        assertNull(endpoints.getFirst().get("connected_via"));
    }

    @Test
    void findRelatedEndpointsShouldDeduplicateEndpoints() {
        var endpointNode = makeNode("ep:getUsers", NodeKind.ENDPOINT, "getUsers");
        // Same endpoint appears as both a direct match and a neighbor
        when(graphStore.search("ep", 50)).thenReturn(List.of(endpointNode));
        when(graphStore.findEndpointNeighborsBatch(List.of("ep:getUsers")))
                .thenReturn(Map.of("ep:getUsers", List.of(endpointNode)));

        Map<String, Object> result = service.findRelatedEndpoints("ep");

        // Should only appear once
        assertEquals(1, result.get("count"));
    }

    // --- nodeToMap ---

    @Test
    void nodeToMapShouldIncludeAllFields() {
        var node = makeNode("n1", NodeKind.ENDPOINT, "getUsers");
        node.setFqn("com.example.getUsers");
        node.setLineStart(10);
        node.setLineEnd(20);
        node.setAnnotations(List.of("@GetMapping"));
        node.setProperties(Map.of("method", "GET"));

        Map<String, Object> map = service.nodeToMap(node);

        assertEquals("n1", map.get("id"));
        assertEquals("endpoint", map.get("kind"));
        assertEquals("getUsers", map.get("label"));
        assertEquals("com.example.getUsers", map.get("fqn"));
        assertEquals("app", map.get("module"));
        assertEquals("src/app.py", map.get("file_path"));
        assertEquals(10, map.get("line_start"));
        assertEquals(20, map.get("line_end"));
        assertEquals("backend", map.get("layer"));
        assertNotNull(map.get("annotations"));
        assertNotNull(map.get("properties"));
    }

    @Test
    void nodeToMapShouldOmitNullFields() {
        var node = new CodeNode("n1", NodeKind.CLASS, "Foo");

        Map<String, Object> map = service.nodeToMap(node);

        assertEquals("n1", map.get("id"));
        assertNull(map.get("fqn"));
        assertNull(map.get("module"));
        assertNull(map.get("file_path"));
        assertNull(map.get("line_start"));
        assertNull(map.get("layer"));
    }
}
