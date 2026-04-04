package io.github.randomcodespace.iq.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import io.github.randomcodespace.iq.query.QueryService;
import io.github.randomcodespace.iq.query.StatsService;
import io.github.randomcodespace.iq.query.TopologyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Expanded McpTools tests targeting coverage gaps:
 * - topology service-level tools (service_detail, service_dependencies, etc.)
 * - getCachedData with node data
 * - run_cypher mutation blocking
 * - get_detailed_stats category handling
 * - find_dead_code with/without limit
 * - get_topology delegation
 * - getCapabilities
 * - error branches
 */
@ExtendWith(MockitoExtension.class)
class McpToolsExpandedTest {

    @Mock
    private QueryService queryService;

    @Mock
    private StatsService statsService;

    @Mock
    private GraphDatabaseService graphDb;

    @Mock
    private GraphStore graphStore;

    private CodeIqConfig config;
    private ObjectMapper objectMapper;
    private McpTools mcpTools;

    @BeforeEach
    void setUp() {
        config = new CodeIqConfig();
        config.setRootPath(".");
        objectMapper = new ObjectMapper();
        mcpTools = new McpTools(
                queryService, config, objectMapper,
                Optional.empty(), graphDb, statsService,
                new TopologyService(), graphStore,
                Optional.empty(), Optional.empty()
        );
    }

    private Map<String, Object> parseJson(String json) throws IOException {
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    // ── get_detailed_stats ──

    @Test
    void getDetailedStatsShouldUseAllByDefault() throws IOException {
        when(queryService.getDetailedStats("all")).thenReturn(Map.of("graph", Map.of("nodes", 10)));
        String result = mcpTools.getDetailedStats(null);
        verify(queryService).getDetailedStats("all");
    }

    @Test
    void getDetailedStatsShouldPassCategory() throws IOException {
        when(queryService.getDetailedStats("frameworks")).thenReturn(Map.of("frameworks", Map.of()));
        mcpTools.getDetailedStats("frameworks");
        verify(queryService).getDetailedStats("frameworks");
    }

    @Test
    void getDetailedStatsShouldReturnErrorOnException() throws IOException {
        when(queryService.getDetailedStats(anyString())).thenThrow(new RuntimeException("failed"));
        String result = mcpTools.getDetailedStats("all");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── find_dead_code ──

    @Test
    void findDeadCodeShouldUseDefaultLimit() throws IOException {
        when(queryService.findDeadCode(null, 100)).thenReturn(Map.of("dead_code", List.of(), "count", 0));
        mcpTools.findDeadCode(null, null);
        verify(queryService).findDeadCode(null, 100);
    }

    @Test
    void findDeadCodeShouldCapLimitAt1000() throws IOException {
        when(queryService.findDeadCode(null, 1000)).thenReturn(Map.of("dead_code", List.of(), "count", 0));
        mcpTools.findDeadCode(null, 5000);
        verify(queryService).findDeadCode(null, 1000);
    }

    @Test
    void findDeadCodeShouldUseKindAndCustomLimit() throws IOException {
        when(queryService.findDeadCode("class", 50)).thenReturn(Map.of("dead_code", List.of(), "count", 0));
        mcpTools.findDeadCode("class", 50);
        verify(queryService).findDeadCode("class", 50);
    }

    // ── run_cypher mutation blocking ──

    @Test
    void runCypherShouldBlockCreateKeyword() throws IOException {
        String result = mcpTools.runCypher("CREATE (n:Node) RETURN n");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
        assertTrue(parsed.get("error").toString().toLowerCase().contains("mutation")
                || parsed.get("error").toString().toLowerCase().contains("read-only"));
    }

    @Test
    void runCypherShouldBlockDeleteKeyword() throws IOException {
        String result = mcpTools.runCypher("MATCH (n) DELETE n");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void runCypherShouldBlockMergeKeyword() throws IOException {
        String result = mcpTools.runCypher("MERGE (n:Node {id: '1'}) RETURN n");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void runCypherShouldBlockSetKeyword() throws IOException {
        String result = mcpTools.runCypher("MATCH (n) SET n.foo = 'bar'");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void runCypherShouldBlockRemove() throws IOException {
        String result = mcpTools.runCypher("MATCH (n) REMOVE n.property");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void runCypherShouldBlockDrop() throws IOException {
        String result = mcpTools.runCypher("DROP CONSTRAINT ON (n:Node)");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void runCypherShouldBlockDetach() throws IOException {
        String result = mcpTools.runCypher("MATCH (n) DETACH DELETE n");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void runCypherShouldBlockForeach() throws IOException {
        String result = mcpTools.runCypher("FOREACH (n IN nodes(p) | SET n.visited = true)");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void runCypherShouldBlockCall() throws IOException {
        String result = mcpTools.runCypher("CALL db.indexes()");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void runCypherShouldAllowMatchReturn() throws IOException {
        Transaction tx = mock(Transaction.class);
        Result queryResult = mock(Result.class);
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute("MATCH (n) RETURN n.id LIMIT 10")).thenReturn(queryResult);
        when(queryResult.columns()).thenReturn(List.of("n.id"));
        when(queryResult.hasNext()).thenReturn(false);

        String result = mcpTools.runCypher("MATCH (n) RETURN n.id LIMIT 10");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("rows"));
        assertEquals(0, parsed.get("count"));
    }

    // ── get_topology (QueryService delegation) ──

    @Test
    void getTopologyShouldDelegate() throws IOException {
        LinkedHashMap<String, Object> topology = new LinkedHashMap<>();
        topology.put("services", List.of());
        topology.put("connections", List.of());
        when(queryService.getTopology()).thenReturn(topology);
        String result = mcpTools.getTopology();
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("services"));
    }

    @Test
    void getTopologyShouldReturnErrorOnException() throws IOException {
        when(queryService.getTopology()).thenThrow(new RuntimeException("DB error"));
        String result = mcpTools.getTopology();
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── topology tools that use getCachedData (with nodes from graphStore) ──

    private CodeNode makeServiceNode(String id, String name) {
        CodeNode n = new CodeNode(id, NodeKind.SERVICE, name);
        n.setFilePath("/project/" + name);
        return n;
    }

    @Test
    void serviceDetailShouldReturnErrorWhenNoData() throws IOException {
        when(graphStore.findAll()).thenThrow(new RuntimeException("No analysis data available. Run 'code-iq analyze' first."));
        String result = mcpTools.serviceDetail("my-service");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void serviceDependenciesShouldReturnErrorWhenNoData() throws IOException {
        when(graphStore.findAll()).thenThrow(new RuntimeException("No analysis data available. Run 'code-iq analyze' first."));
        String result = mcpTools.serviceDependencies("my-service");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void serviceDependentsShouldReturnErrorWhenNoData() throws IOException {
        when(graphStore.findAll()).thenThrow(new RuntimeException("No analysis data available. Run 'code-iq analyze' first."));
        String result = mcpTools.serviceDependents("my-service");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void blastRadiusShouldReturnErrorWhenNoData() throws IOException {
        when(graphStore.findAll()).thenThrow(new RuntimeException("No analysis data available. Run 'code-iq analyze' first."));
        String result = mcpTools.blastRadius("node-1");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void findPathShouldReturnErrorWhenNoData() throws IOException {
        when(graphStore.findAll()).thenThrow(new RuntimeException("No analysis data available. Run 'code-iq analyze' first."));
        String result = mcpTools.findPath("service-a", "service-b");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void findBottlenecksShouldReturnErrorWhenNoData() throws IOException {
        when(graphStore.findAll()).thenThrow(new RuntimeException("No analysis data available. Run 'code-iq analyze' first."));
        String result = mcpTools.findBottlenecks();
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void findCircularDepsShouldReturnErrorWhenNoData() throws IOException {
        when(graphStore.findAll()).thenThrow(new RuntimeException("No analysis data available. Run 'code-iq analyze' first."));
        String result = mcpTools.findCircularDeps();
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void findDeadServicesShouldReturnErrorWhenNoData() throws IOException {
        when(graphStore.findAll()).thenThrow(new RuntimeException("No analysis data available. Run 'code-iq analyze' first."));
        String result = mcpTools.findDeadServices();
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    @Test
    void findNodeShouldReturnErrorWhenNoData() throws IOException {
        when(graphStore.findAll()).thenThrow(new RuntimeException("No analysis data available. Run 'code-iq analyze' first."));
        String result = mcpTools.findNode("UserService");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── topology tools with actual data ──

    @Test
    void serviceDetailShouldWorkWithEmptyNodeList() throws IOException {
        // Returns empty list (not exception) → getCachedData throws because nodes.isEmpty()
        when(graphStore.findAll()).thenReturn(List.of());
        String result = mcpTools.serviceDetail("my-service");
        Map<String, Object> parsed = parseJson(result);
        // With empty nodes, getCachedData throws RuntimeException
        assertNotNull(parsed.get("error"));
    }

    @Test
    void serviceDetailShouldWorkWithData() throws IOException {
        CodeNode svc = makeServiceNode("svc:api", "api");
        when(graphStore.findAll()).thenReturn(List.of(svc));

        String result = mcpTools.serviceDetail("api");
        assertNotNull(result);
        // Should not throw — result is valid JSON
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed);
    }

    @Test
    void findBottlenecksShouldWorkWithData() throws IOException {
        CodeNode svc = makeServiceNode("svc:api", "api");
        when(graphStore.findAll()).thenReturn(List.of(svc));

        String result = mcpTools.findBottlenecks();
        assertNotNull(result);
        // Result could be array or map — just verify it's valid JSON
        assertFalse(result.isEmpty());
    }

    @Test
    void findCircularDepsShouldWorkWithData() throws IOException {
        CodeNode svc = makeServiceNode("svc:api", "api");
        when(graphStore.findAll()).thenReturn(List.of(svc));

        String result = mcpTools.findCircularDeps();
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void findDeadServicesShouldWorkWithData() throws IOException {
        CodeNode svc = makeServiceNode("svc:api", "api");
        when(graphStore.findAll()).thenReturn(List.of(svc));

        String result = mcpTools.findDeadServices();
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void findNodeShouldWorkWithData() throws IOException {
        CodeNode svc = makeServiceNode("svc:api", "api");
        when(graphStore.findAll()).thenReturn(List.of(svc));

        String result = mcpTools.findNode("api");
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    // ── getCapabilities ──

    @Test
    void getCapabilitiesShouldReturnFullMatrix() throws IOException {
        String result = mcpTools.getCapabilities(null);
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("matrix"));
    }

    @Test
    void getCapabilitiesShouldFilterByLanguage() throws IOException {
        String result = mcpTools.getCapabilities("java");
        Map<String, Object> parsed = parseJson(result);
        assertEquals("java", parsed.get("language"));
        assertNotNull(parsed.get("capabilities"));
    }

    @Test
    void getCapabilitiesShouldHandleBlankLanguage() throws IOException {
        String result = mcpTools.getCapabilities("  ");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("matrix"));
    }

    // ── get_evidence_pack when assembler is null ──

    @Test
    void getEvidencePackShouldReturnErrorWhenAssemblerNull() throws IOException {
        // McpTools was created with Optional.empty() for evidencePackAssembler
        String result = mcpTools.getEvidencePack("UserService", null, null, null);
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
        assertTrue(parsed.get("error").toString().contains("unavailable")
                || parsed.get("error").toString().contains("enrich"));
    }

    // ── get_artifact_metadata when metadata is null ──

    @Test
    void getArtifactMetadataShouldReturnErrorWhenNull() throws IOException {
        // McpTools was created with Optional.empty() for artifactMetadata
        String result = mcpTools.getArtifactMetadata();
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── generate_flow with no flow engine and no cache data ──

    @Test
    void generateFlowShouldReturnErrorWhenNoEngineAndNoData() throws IOException {
        // graphStore.findAll() throws (empty)
        when(graphStore.findAll()).thenReturn(List.of());
        String result = mcpTools.generateFlow("overview", "json");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── get_stats error handling ──

    @Test
    void getStatsShouldReturnErrorOnException() throws IOException {
        when(queryService.getStats()).thenThrow(new RuntimeException("Neo4j down"));
        String result = mcpTools.getStats();
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── search_graph error handling ──

    @Test
    void searchGraphShouldReturnErrorOnException() throws IOException {
        when(queryService.searchGraph(anyString(), anyInt())).thenThrow(new RuntimeException("index error"));
        String result = mcpTools.searchGraph("User", null);
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── find_callers error handling ──

    @Test
    void findCallersShouldReturnErrorOnException() throws IOException {
        when(queryService.callersOf("fn1")).thenThrow(new RuntimeException("DB error"));
        String result = mcpTools.findCallers("fn1");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── trace_impact error handling ──

    @Test
    void traceImpactShouldReturnErrorOnException() throws IOException {
        when(queryService.traceImpact("n1", 3)).thenThrow(new RuntimeException("timeout"));
        String result = mcpTools.traceImpact("n1", null);
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── find_consumers error handling ──

    @Test
    void findConsumersShouldReturnErrorOnException() throws IOException {
        when(queryService.consumersOf("t1")).thenThrow(new RuntimeException("error"));
        String result = mcpTools.findConsumers("t1");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── find_dependencies error handling ──

    @Test
    void findDependenciesShouldReturnErrorOnException() throws IOException {
        when(queryService.dependenciesOf("mod1")).thenThrow(new RuntimeException("error"));
        String result = mcpTools.findDependencies("mod1");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── find_dependents error handling ──

    @Test
    void findDependentsShouldReturnErrorOnException() throws IOException {
        when(queryService.dependentsOf("mod1")).thenThrow(new RuntimeException("error"));
        String result = mcpTools.findDependents("mod1");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── get_node_neighbors error handling ──

    @Test
    void getNodeNeighborsShouldReturnErrorOnException() throws IOException {
        when(queryService.getNeighbors(anyString(), anyString())).thenThrow(new RuntimeException("error"));
        String result = mcpTools.getNodeNeighbors("n1", "both");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── get_ego_graph error handling ──

    @Test
    void getEgoGraphShouldReturnErrorOnException() throws IOException {
        when(queryService.egoGraph(anyString(), anyInt())).thenThrow(new RuntimeException("error"));
        String result = mcpTools.getEgoGraph("n1", 2);
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── find_cycles error handling ──

    @Test
    void findCyclesShouldReturnErrorOnException() throws IOException {
        when(queryService.findCycles(anyInt())).thenThrow(new RuntimeException("cycle error"));
        String result = mcpTools.findCycles(100);
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── find_shortest_path error handling ──

    @Test
    void findShortestPathShouldReturnErrorOnException() throws IOException {
        when(queryService.shortestPath(anyString(), anyString())).thenThrow(new RuntimeException("path error"));
        String result = mcpTools.findShortestPath("a", "b");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── find_component_by_file error handling ──

    @Test
    void findComponentByFileShouldReturnErrorOnException() throws IOException {
        when(queryService.findComponentByFile(anyString())).thenThrow(new RuntimeException("error"));
        String result = mcpTools.findComponentByFile("src/app.py");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── find_related_endpoints error handling ──

    @Test
    void findRelatedEndpointsShouldReturnErrorOnException() throws IOException {
        when(queryService.findRelatedEndpoints(anyString())).thenThrow(new RuntimeException("error"));
        String result = mcpTools.findRelatedEndpoints("UserService");
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── query_nodes error handling ──

    @Test
    void queryNodesShouldReturnErrorOnException() throws IOException {
        when(queryService.listNodes(anyString(), anyInt(), anyInt())).thenThrow(new RuntimeException("error"));
        String result = mcpTools.queryNodes("class", 10);
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }

    // ── query_edges error handling ──

    @Test
    void queryEdgesShouldReturnErrorOnException() throws IOException {
        when(queryService.listEdges(anyString(), anyInt(), anyInt())).thenThrow(new RuntimeException("error"));
        String result = mcpTools.queryEdges("calls", 10);
        Map<String, Object> parsed = parseJson(result);
        assertNotNull(parsed.get("error"));
    }
}
