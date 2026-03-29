package io.github.randomcodespace.iq.query;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StatsServiceTest {

    private StatsService service;

    @BeforeEach
    void setUp() {
        service = new StatsService();
    }

    private CodeNode makeNode(String id, NodeKind kind, String label, String filePath) {
        var node = new CodeNode(id, kind, label);
        node.setFilePath(filePath);
        node.setProperties(new HashMap<>());
        return node;
    }

    private CodeEdge makeEdge(String sourceId, String targetId, EdgeKind kind) {
        var target = new CodeNode(targetId, NodeKind.CLASS, "T");
        return new CodeEdge("edge:" + sourceId + ":" + targetId, kind, sourceId, target);
    }

    // --- computeStats full ---

    @Test
    void computeStatsReturnsAllCategories() {
        var nodes = List.of(makeNode("n1", NodeKind.CLASS, "Foo", "src/Foo.java"));
        var edges = List.<CodeEdge>of();

        Map<String, Object> stats = service.computeStats(nodes, edges);

        assertTrue(stats.containsKey("graph"));
        assertTrue(stats.containsKey("languages"));
        assertTrue(stats.containsKey("frameworks"));
        assertTrue(stats.containsKey("infra"));
        assertTrue(stats.containsKey("connections"));
        assertTrue(stats.containsKey("auth"));
        assertTrue(stats.containsKey("architecture"));
    }

    // --- computeGraph ---

    @Test
    void computeGraphCountsNodesEdgesFiles() {
        var nodes = List.of(
                makeNode("n1", NodeKind.CLASS, "A", "src/A.java"),
                makeNode("n2", NodeKind.METHOD, "B", "src/A.java"),
                makeNode("n3", NodeKind.CLASS, "C", "src/C.java")
        );
        var edges = List.of(
                makeEdge("n1", "n2", EdgeKind.CONTAINS),
                makeEdge("n1", "n3", EdgeKind.DEPENDS_ON)
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> graph = service.computeGraph(nodes, edges);

        assertEquals(3, ((Number) graph.get("nodes")).intValue());
        assertEquals(2, ((Number) graph.get("edges")).intValue());
        assertEquals(2L, graph.get("files")); // two unique file paths
    }

    // --- computeLanguages ---

    @Test
    void computeLanguagesGroupsByExtension() {
        var nodes = List.of(
                makeNode("n1", NodeKind.CLASS, "A", "src/A.java"),
                makeNode("n2", NodeKind.CLASS, "B", "src/B.java"),
                makeNode("n3", NodeKind.CLASS, "C", "src/C.kt")
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> langs = service.computeLanguages(nodes);

        assertEquals(2L, langs.get("java"));
        assertEquals(1L, langs.get("kotlin"));
    }

    @Test
    void computeLanguagesPrefersPropertyOverExtension() {
        var node = makeNode("n1", NodeKind.CLASS, "A", "src/A.java");
        node.getProperties().put("language", "kotlin");

        @SuppressWarnings("unchecked")
        Map<String, Object> langs = service.computeLanguages(List.of(node));

        assertEquals(1L, langs.get("kotlin"));
        assertNull(langs.get("java"));
    }

    // --- computeFrameworks ---

    @Test
    void computeFrameworksGroupsByProperty() {
        var n1 = makeNode("n1", NodeKind.ENDPOINT, "e1", "src/A.java");
        n1.getProperties().put("framework", "Spring Security");
        var n2 = makeNode("n2", NodeKind.ENDPOINT, "e2", "src/B.java");
        n2.getProperties().put("framework", "Spring Security");
        var n3 = makeNode("n3", NodeKind.ENDPOINT, "e3", "src/C.java");
        n3.getProperties().put("framework", "Micronaut");

        @SuppressWarnings("unchecked")
        Map<String, Object> fws = service.computeFrameworks(List.of(n1, n2, n3));

        assertEquals(2L, fws.get("Spring Security"));
        assertEquals(1L, fws.get("Micronaut"));
    }

    @Test
    void computeFrameworksSkipsBlankValues() {
        var node = makeNode("n1", NodeKind.CLASS, "A", "src/A.java");
        node.getProperties().put("framework", "  ");

        Map<String, Object> fws = service.computeFrameworks(List.of(node));
        assertTrue(fws.isEmpty());
    }

    // --- computeInfra ---

    @Test
    void computeInfraGroupsDatabases() {
        var n1 = makeNode("n1", NodeKind.DATABASE_CONNECTION, "pg", "src/A.java");
        n1.getProperties().put("db_type", "PostgreSQL");
        var n2 = makeNode("n2", NodeKind.DATABASE_CONNECTION, "h2", "src/B.java");
        n2.getProperties().put("db_type", "H2");
        var n3 = makeNode("n3", NodeKind.DATABASE_CONNECTION, "pg2", "src/C.java");
        n3.getProperties().put("db_type", "PostgreSQL");

        @SuppressWarnings("unchecked")
        Map<String, Object> infra = service.computeInfra(List.of(n1, n2, n3));
        @SuppressWarnings("unchecked")
        Map<String, Long> dbs = (Map<String, Long>) infra.get("databases");

        assertEquals(2L, dbs.get("PostgreSQL"));
        assertEquals(1L, dbs.get("H2"));
    }

    @Test
    void computeInfraGroupsMessaging() {
        var n1 = makeNode("n1", NodeKind.TOPIC, "t1", "src/A.java");
        n1.getProperties().put("protocol", "kafka");
        var n2 = makeNode("n2", NodeKind.QUEUE, "q1", "src/B.java");
        n2.getProperties().put("protocol", "rabbitmq");

        @SuppressWarnings("unchecked")
        Map<String, Object> infra = service.computeInfra(List.of(n1, n2));
        @SuppressWarnings("unchecked")
        Map<String, Long> msg = (Map<String, Long>) infra.get("messaging");

        assertEquals(1L, msg.get("kafka"));
        assertEquals(1L, msg.get("rabbitmq"));
    }

    @Test
    void computeInfraGroupsCloud() {
        var n1 = makeNode("n1", NodeKind.AZURE_RESOURCE, "hub", "src/A.java");
        n1.getProperties().put("resource_type", "Event Hub");
        var n2 = makeNode("n2", NodeKind.INFRA_RESOURCE, "vm", "src/B.java");
        n2.getProperties().put("resource_type", "VM");

        @SuppressWarnings("unchecked")
        Map<String, Object> infra = service.computeInfra(List.of(n1, n2));
        @SuppressWarnings("unchecked")
        Map<String, Long> cloud = (Map<String, Long>) infra.get("cloud");

        assertEquals(1L, cloud.get("Event Hub"));
        assertEquals(1L, cloud.get("VM"));
    }

    // --- computeConnections ---

    @Test
    void computeConnectionsCountsRestByMethod() {
        var n1 = makeNode("n1", NodeKind.ENDPOINT, "e1", "src/A.java");
        n1.getProperties().put("http_method", "GET");
        var n2 = makeNode("n2", NodeKind.ENDPOINT, "e2", "src/B.java");
        n2.getProperties().put("http_method", "POST");
        var n3 = makeNode("n3", NodeKind.ENDPOINT, "e3", "src/C.java");
        n3.getProperties().put("http_method", "GET");

        @SuppressWarnings("unchecked")
        Map<String, Object> conn = service.computeConnections(List.of(n1, n2, n3), List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> rest = (Map<String, Object>) conn.get("rest");

        assertEquals(3L, rest.get("total"));
        @SuppressWarnings("unchecked")
        Map<String, Long> byMethod = (Map<String, Long>) rest.get("by_method");
        assertEquals(2L, byMethod.get("GET"));
        assertEquals(1L, byMethod.get("POST"));
    }

    @Test
    void computeConnectionsCountsGrpc() {
        var n1 = makeNode("n1", NodeKind.ENDPOINT, "grpc1", "src/A.java");
        n1.getProperties().put("protocol", "grpc");

        @SuppressWarnings("unchecked")
        Map<String, Object> conn = service.computeConnections(List.of(n1), List.of());

        assertEquals(1L, conn.get("grpc"));
    }

    @Test
    void computeConnectionsCountsWebSocket() {
        var n1 = makeNode("n1", NodeKind.WEBSOCKET_ENDPOINT, "ws1", "src/A.java");

        @SuppressWarnings("unchecked")
        Map<String, Object> conn = service.computeConnections(List.of(n1), List.of());

        assertEquals(1L, conn.get("websocket"));
    }

    @Test
    void computeConnectionsCountsProducersConsumers() {
        var edges = List.of(
                makeEdge("a", "b", EdgeKind.PRODUCES),
                makeEdge("c", "d", EdgeKind.PUBLISHES),
                makeEdge("e", "f", EdgeKind.CONSUMES),
                makeEdge("g", "h", EdgeKind.LISTENS)
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> conn = service.computeConnections(List.of(), edges);

        assertEquals(2L, conn.get("producers"));
        assertEquals(2L, conn.get("consumers"));
    }

    // --- computeAuth ---

    @Test
    void computeAuthGroupsByType() {
        var n1 = makeNode("n1", NodeKind.GUARD, "g1", "src/A.java");
        n1.getProperties().put("auth_type", "spring_security");
        var n2 = makeNode("n2", NodeKind.GUARD, "g2", "src/B.java");
        n2.getProperties().put("auth_type", "spring_security");
        var n3 = makeNode("n3", NodeKind.GUARD, "g3", "src/C.java");
        n3.getProperties().put("auth_type", "ldap");

        @SuppressWarnings("unchecked")
        Map<String, Object> auth = service.computeAuth(List.of(n1, n2, n3));

        assertEquals(2L, auth.get("spring_security"));
        assertEquals(1L, auth.get("ldap"));
    }

    // --- computeArchitecture ---

    @Test
    void computeArchitectureCountsByKind() {
        var nodes = List.of(
                makeNode("n1", NodeKind.CLASS, "A", "src/A.java"),
                makeNode("n2", NodeKind.CLASS, "B", "src/B.java"),
                makeNode("n3", NodeKind.INTERFACE, "I", "src/I.java"),
                makeNode("n4", NodeKind.ABSTRACT_CLASS, "Ab", "src/Ab.java"),
                makeNode("n5", NodeKind.ENUM, "E", "src/E.java"),
                makeNode("n6", NodeKind.MODULE, "M", "src/M.java"),
                makeNode("n7", NodeKind.METHOD, "m", "src/A.java"),
                makeNode("n8", NodeKind.ANNOTATION_TYPE, "Ann", "src/Ann.java")
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> arch = service.computeArchitecture(nodes);

        assertEquals(2L, arch.get("classes"));
        assertEquals(1L, arch.get("interfaces"));
        assertEquals(1L, arch.get("abstract_classes"));
        assertEquals(1L, arch.get("enums"));
        assertEquals(1L, arch.get("modules"));
        assertEquals(1L, arch.get("methods"));
        assertEquals(1L, arch.get("annotation_types"));
    }

    @Test
    void computeArchitectureOmitsZeroCounts() {
        var nodes = List.of(makeNode("n1", NodeKind.CLASS, "A", "src/A.java"));

        Map<String, Object> arch = service.computeArchitecture(nodes);

        assertTrue(arch.containsKey("classes"));
        assertFalse(arch.containsKey("interfaces"));
        assertFalse(arch.containsKey("enums"));
    }

    // --- computeCategory ---

    @Test
    void computeCategoryReturnsCorrectCategory() {
        var nodes = List.of(makeNode("n1", NodeKind.CLASS, "A", "src/A.java"));
        var edges = List.<CodeEdge>of();

        Map<String, Object> graph = service.computeCategory(nodes, edges, "graph");
        assertNotNull(graph);
        assertEquals(1, ((Number) graph.get("nodes")).intValue());

        Map<String, Object> arch = service.computeCategory(nodes, edges, "architecture");
        assertNotNull(arch);
        assertTrue(arch.containsKey("classes"));
    }

    @Test
    void computeCategoryReturnsNullForUnknown() {
        assertNull(service.computeCategory(List.of(), List.of(), "bogus"));
    }

    // --- sortByValueDesc ---

    @Test
    void sortByValueDescSortsCorrectly() {
        Map<String, Long> input = new java.util.LinkedHashMap<>();
        input.put("a", 1L);
        input.put("b", 3L);
        input.put("c", 2L);

        Map<String, Long> sorted = StatsService.sortByValueDesc(input);
        List<String> keys = new ArrayList<>(sorted.keySet());

        assertEquals("b", keys.get(0));
        assertEquals("c", keys.get(1));
        assertEquals("a", keys.get(2));
    }
}
