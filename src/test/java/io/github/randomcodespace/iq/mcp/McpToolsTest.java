package io.github.randomcodespace.iq.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.randomcodespace.iq.analyzer.AnalysisResult;
import io.github.randomcodespace.iq.analyzer.Analyzer;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.flow.FlowModels.FlowDiagram;
import io.github.randomcodespace.iq.flow.FlowModels.FlowEdge;
import io.github.randomcodespace.iq.flow.FlowModels.FlowNode;
import io.github.randomcodespace.iq.flow.FlowModels.FlowSubgraph;
import io.github.randomcodespace.iq.query.QueryService;
import io.github.randomcodespace.iq.query.StatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.graphdb.GraphDatabaseService;
import org.neo4j.graphdb.Result;
import org.neo4j.graphdb.Transaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class McpToolsTest {

    @Mock
    private QueryService queryService;

    @Mock
    private Analyzer analyzer;

    @Mock
    private FlowEngine flowEngine;

    @Mock
    private GraphDatabaseService graphDb;

    @Mock
    private StatsService statsService;

    private CodeIqConfig config;
    private ObjectMapper objectMapper;
    private McpTools mcpTools;

    @BeforeEach
    void setUp() {
        config = new CodeIqConfig();
        config.setRootPath(".");
        objectMapper = new ObjectMapper();
        mcpTools = new McpTools(queryService, analyzer, config, objectMapper, java.util.Optional.ofNullable(flowEngine), graphDb, statsService, new io.github.randomcodespace.iq.query.TopologyService());
    }

    private Map<String, Object> parseJson(String json) throws IOException {
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    private List<Object> parseJsonArray(String json) throws IOException {
        return objectMapper.readValue(json, new TypeReference<>() {});
    }

    // --- get_stats ---

    @Test
    void getStatsShouldReturnJson() throws IOException {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("node_count", 42L);
        stats.put("edge_count", 10L);
        when(queryService.getStats()).thenReturn(stats);

        String result = mcpTools.getStats();
        Map<String, Object> parsed = parseJson(result);

        assertEquals(42, parsed.get("node_count"));
        assertEquals(10, parsed.get("edge_count"));
    }

    // --- query_nodes ---

    @Test
    void queryNodesShouldDelegateToQueryService() throws IOException {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("nodes", List.of());
        nodes.put("count", 0);
        when(queryService.listNodes("endpoint", 50, 0)).thenReturn(nodes);

        String result = mcpTools.queryNodes("endpoint", null);
        Map<String, Object> parsed = parseJson(result);

        assertEquals(0, parsed.get("count"));
        verify(queryService).listNodes("endpoint", 50, 0);
    }

    @Test
    void queryNodesShouldUseCustomLimit() throws IOException {
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodes.put("nodes", List.of());
        nodes.put("count", 0);
        when(queryService.listNodes(null, 25, 0)).thenReturn(nodes);

        mcpTools.queryNodes(null, 25);

        verify(queryService).listNodes(null, 25, 0);
    }

    // --- query_edges ---

    @Test
    void queryEdgesShouldDelegateToQueryService() throws IOException {
        Map<String, Object> edges = new LinkedHashMap<>();
        edges.put("edges", List.of());
        edges.put("count", 0);
        when(queryService.listEdges("calls", 50, 0)).thenReturn(edges);

        String result = mcpTools.queryEdges("calls", null);
        Map<String, Object> parsed = parseJson(result);

        assertEquals(0, parsed.get("count"));
    }

    // --- get_node_neighbors ---

    @Test
    void getNodeNeighborsShouldDefaultToBoth() throws IOException {
        Map<String, Object> neighbors = new LinkedHashMap<>();
        neighbors.put("direction", "both");
        neighbors.put("neighbors", List.of());
        when(queryService.getNeighbors("n1", "both")).thenReturn(neighbors);

        String result = mcpTools.getNodeNeighbors("n1", null);
        Map<String, Object> parsed = parseJson(result);

        assertEquals("both", parsed.get("direction"));
    }

    @Test
    void getNodeNeighborsShouldUseSpecifiedDirection() throws IOException {
        Map<String, Object> neighbors = new LinkedHashMap<>();
        neighbors.put("direction", "out");
        neighbors.put("neighbors", List.of());
        when(queryService.getNeighbors("n1", "out")).thenReturn(neighbors);

        mcpTools.getNodeNeighbors("n1", "out");

        verify(queryService).getNeighbors("n1", "out");
    }

    // --- get_ego_graph ---

    @Test
    void getEgoGraphShouldDefaultRadius() throws IOException {
        Map<String, Object> ego = new LinkedHashMap<>();
        ego.put("center", "n1");
        ego.put("radius", 2);
        when(queryService.egoGraph("n1", 2)).thenReturn(ego);

        String result = mcpTools.getEgoGraph("n1", null);
        Map<String, Object> parsed = parseJson(result);

        assertEquals("n1", parsed.get("center"));
    }

    // --- find_cycles ---

    @Test
    void findCyclesShouldDelegateToQueryService() throws IOException {
        Map<String, Object> cycles = new LinkedHashMap<>();
        cycles.put("cycles", List.of());
        cycles.put("count", 0);
        when(queryService.findCycles(100)).thenReturn(cycles);

        String result = mcpTools.findCycles(null);
        Map<String, Object> parsed = parseJson(result);

        assertEquals(0, parsed.get("count"));
    }

    // --- find_shortest_path ---

    @Test
    void findShortestPathShouldReturnPath() throws IOException {
        Map<String, Object> path = new LinkedHashMap<>();
        path.put("source", "a");
        path.put("target", "b");
        path.put("path", List.of("a", "c", "b"));
        when(queryService.shortestPath("a", "b")).thenReturn(path);

        String result = mcpTools.findShortestPath("a", "b");
        Map<String, Object> parsed = parseJson(result);

        assertEquals("a", parsed.get("source"));
    }

    @Test
    void findShortestPathShouldReturnErrorWhenNoPath() throws IOException {
        when(queryService.shortestPath("a", "b")).thenReturn(null);

        String result = mcpTools.findShortestPath("a", "b");
        Map<String, Object> parsed = parseJson(result);

        assertNotNull(parsed.get("error"));
    }

    // --- find_consumers ---

    @Test
    void findConsumersShouldDelegateToQueryService() throws IOException {
        Map<String, Object> consumers = new LinkedHashMap<>();
        consumers.put("target", "t1");
        consumers.put("consumers", List.of());
        when(queryService.consumersOf("t1")).thenReturn(consumers);

        String result = mcpTools.findConsumers("t1");
        Map<String, Object> parsed = parseJson(result);

        assertEquals("t1", parsed.get("target"));
    }

    // --- find_producers ---

    @Test
    void findProducersShouldDelegateToQueryService() throws IOException {
        Map<String, Object> producers = new LinkedHashMap<>();
        producers.put("target", "t1");
        producers.put("producers", List.of());
        when(queryService.producersOf("t1")).thenReturn(producers);

        String result = mcpTools.findProducers("t1");
        Map<String, Object> parsed = parseJson(result);

        assertEquals("t1", parsed.get("target"));
    }

    // --- find_callers ---

    @Test
    void findCallersShouldDelegateToQueryService() throws IOException {
        Map<String, Object> callers = new LinkedHashMap<>();
        callers.put("target", "fn1");
        callers.put("callers", List.of());
        when(queryService.callersOf("fn1")).thenReturn(callers);

        String result = mcpTools.findCallers("fn1");
        Map<String, Object> parsed = parseJson(result);

        assertEquals("fn1", parsed.get("target"));
    }

    // --- find_dependencies ---

    @Test
    void findDependenciesShouldDelegateToQueryService() throws IOException {
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("module", "mod1");
        deps.put("dependencies", List.of());
        when(queryService.dependenciesOf("mod1")).thenReturn(deps);

        String result = mcpTools.findDependencies("mod1");
        Map<String, Object> parsed = parseJson(result);

        assertEquals("mod1", parsed.get("module"));
    }

    // --- find_dependents ---

    @Test
    void findDependentsShouldDelegateToQueryService() throws IOException {
        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("module", "mod1");
        deps.put("dependents", List.of());
        when(queryService.dependentsOf("mod1")).thenReturn(deps);

        String result = mcpTools.findDependents("mod1");
        Map<String, Object> parsed = parseJson(result);

        assertEquals("mod1", parsed.get("module"));
    }

    // --- generate_flow ---

    @Test
    void generateFlowShouldCallFlowEngine() throws IOException {
        FlowDiagram diagram = new FlowDiagram(
                "overview", "Overview", "TB",
                List.of(new FlowSubgraph("sg1", "SG1", List.of(), null)),
                List.of(), List.of(), Map.of()
        );
        when(flowEngine.generate("overview")).thenReturn(diagram);
        when(flowEngine.render(diagram, "json")).thenReturn("{\"title\":\"Overview\"}");

        String result = mcpTools.generateFlow("overview", "json");

        assertEquals("{\"title\":\"Overview\"}", result);
        verify(flowEngine).generate("overview");
        verify(flowEngine).render(diagram, "json");
    }

    @Test
    void generateFlowShouldDefaultViewAndFormat() throws IOException {
        FlowDiagram diagram = new FlowDiagram(
                "overview", "Overview", "TB",
                List.of(), List.of(), List.of(), Map.of()
        );
        when(flowEngine.generate("overview")).thenReturn(diagram);
        when(flowEngine.render(diagram, "json")).thenReturn("{}");

        mcpTools.generateFlow(null, null);

        verify(flowEngine).generate("overview");
        verify(flowEngine).render(diagram, "json");
    }

    @Test
    void generateFlowShouldHandleInvalidView() throws IOException {
        when(flowEngine.generate("nonexistent"))
                .thenThrow(new IllegalArgumentException("Unknown view: nonexistent"));

        String result = mcpTools.generateFlow("nonexistent", "json");
        Map<String, Object> parsed = parseJson(result);

        assertNotNull(parsed.get("error"));
        assertTrue(parsed.get("error").toString().contains("Unknown view"));
    }

    // --- analyze_codebase ---

    @Test
    void analyzeCodebaseShouldReturnResult() throws IOException {
        var analysisResult = new AnalysisResult(
                100, 80, 500, 200, Map.of(), Map.of(), Map.of(), Map.of(), Duration.ofMillis(1500)
        );
        when(analyzer.run(any(), any(), anyBoolean(), any())).thenReturn(analysisResult);

        String result = mcpTools.analyzeCodebase(false);
        Map<String, Object> parsed = parseJson(result);

        assertEquals("complete", parsed.get("status"));
        assertEquals(500, parsed.get("node_count"));
    }

    @Test
    void analyzeCodebaseShouldHandleError() throws IOException {
        when(analyzer.run(any(), any(), anyBoolean(), any())).thenThrow(new RuntimeException("Analysis failed"));

        String result = mcpTools.analyzeCodebase(false);
        Map<String, Object> parsed = parseJson(result);

        assertNotNull(parsed.get("error"));
    }

    // --- run_cypher ---

    @Test
    void runCypherShouldExecuteQuery() throws IOException {
        Transaction tx = mock(Transaction.class);
        Result queryResult = mock(Result.class);

        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute("MATCH (n) RETURN count(n) as cnt")).thenReturn(queryResult);
        when(queryResult.columns()).thenReturn(List.of("cnt"));
        when(queryResult.hasNext()).thenReturn(true, false);
        when(queryResult.next()).thenReturn(Map.of("cnt", 42L));

        String result = mcpTools.runCypher("MATCH (n) RETURN count(n) as cnt");
        Map<String, Object> parsed = parseJson(result);

        assertEquals(1, parsed.get("count"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) parsed.get("rows");
        assertEquals(42, rows.getFirst().get("cnt"));
        verify(tx).commit();
    }

    @Test
    void runCypherShouldHandleError() throws IOException {
        Transaction tx = mock(Transaction.class);
        when(graphDb.beginTx()).thenReturn(tx);
        when(tx.execute(anyString())).thenThrow(new RuntimeException("Syntax error"));

        String result = mcpTools.runCypher("INVALID CYPHER");
        Map<String, Object> parsed = parseJson(result);

        assertNotNull(parsed.get("error"));
    }

    // --- find_component_by_file ---

    @Test
    void findComponentByFileShouldDelegateToQueryService() throws IOException {
        Map<String, Object> component = new LinkedHashMap<>();
        component.put("file", "src/app.py");
        component.put("nodes", List.of());
        component.put("count", 0);
        when(queryService.findComponentByFile("src/app.py")).thenReturn(component);

        String result = mcpTools.findComponentByFile("src/app.py");
        Map<String, Object> parsed = parseJson(result);

        assertEquals("src/app.py", parsed.get("file"));
    }

    // --- trace_impact ---

    @Test
    void traceImpactShouldDefaultDepth() throws IOException {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("source", "n1");
        impact.put("depth", 3);
        when(queryService.traceImpact("n1", 3)).thenReturn(impact);

        String result = mcpTools.traceImpact("n1", null);
        Map<String, Object> parsed = parseJson(result);

        assertEquals("n1", parsed.get("source"));
        verify(queryService).traceImpact("n1", 3);
    }

    @Test
    void traceImpactShouldUseCustomDepth() throws IOException {
        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("source", "n1");
        impact.put("depth", 5);
        when(queryService.traceImpact("n1", 5)).thenReturn(impact);

        mcpTools.traceImpact("n1", 5);

        verify(queryService).traceImpact("n1", 5);
    }

    // --- find_related_endpoints ---

    @Test
    void findRelatedEndpointsShouldSearchAndReturn() throws IOException {
        List<Map<String, Object>> searchResults = List.of(
                Map.of("id", "n1", "kind", "endpoint")
        );
        when(queryService.searchGraph("UserService", 50)).thenReturn(searchResults);

        String result = mcpTools.findRelatedEndpoints("UserService");
        Map<String, Object> parsed = parseJson(result);

        assertEquals("UserService", parsed.get("identifier"));
        assertEquals(1, parsed.get("count"));
    }

    // --- search_graph ---

    @Test
    void searchGraphShouldDefaultLimit() throws IOException {
        when(queryService.searchGraph("User", 20)).thenReturn(List.of());

        mcpTools.searchGraph("User", null);

        verify(queryService).searchGraph("User", 20);
    }

    @Test
    void searchGraphShouldUseCustomLimit() throws IOException {
        when(queryService.searchGraph("User", 100)).thenReturn(List.of());

        mcpTools.searchGraph("User", 100);

        verify(queryService).searchGraph("User", 100);
    }

    // --- read_file ---

    @Test
    void readFileShouldReadContent(@TempDir Path tempDir) throws IOException {
        config.setRootPath(tempDir.toString());
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "Hello, World!");

        String result = mcpTools.readFile("test.txt", null, null);

        assertEquals("Hello, World!", result);
    }

    @Test
    void readFileShouldRejectPathTraversal(@TempDir Path tempDir) {
        config.setRootPath(tempDir.toString());

        String result = mcpTools.readFile("../../etc/passwd", null, null);

        assertEquals("Error: Path traversal detected", result);
    }

    @Test
    void readFileShouldHandleMissingFile(@TempDir Path tempDir) {
        config.setRootPath(tempDir.toString());

        String result = mcpTools.readFile("nonexistent.txt", null, null);

        assertTrue(result.startsWith("Error:"));
    }

    @Test
    void readFileShouldReturnLineRange(@TempDir Path tempDir) throws IOException {
        config.setRootPath(tempDir.toString());
        Path file = tempDir.resolve("lines.txt");
        Files.writeString(file, "line1\nline2\nline3\nline4\nline5");

        String result = mcpTools.readFile("lines.txt", 2, 4);

        assertEquals("line2\nline3\nline4", result);
    }

    @Test
    void readFileShouldReturnFromStartLineToEnd(@TempDir Path tempDir) throws IOException {
        config.setRootPath(tempDir.toString());
        Path file = tempDir.resolve("lines.txt");
        Files.writeString(file, "line1\nline2\nline3\nline4\nline5");

        String result = mcpTools.readFile("lines.txt", 3, null);

        assertEquals("line3\nline4\nline5", result);
    }

    @Test
    void readFileShouldReturnFromStartToEndLine(@TempDir Path tempDir) throws IOException {
        config.setRootPath(tempDir.toString());
        Path file = tempDir.resolve("lines.txt");
        Files.writeString(file, "line1\nline2\nline3\nline4\nline5");

        String result = mcpTools.readFile("lines.txt", null, 2);

        assertEquals("line1\nline2", result);
    }

    @Test
    void readFileShouldClampOutOfBoundsLineRange(@TempDir Path tempDir) throws IOException {
        config.setRootPath(tempDir.toString());
        Path file = tempDir.resolve("lines.txt");
        Files.writeString(file, "line1\nline2\nline3");

        String result = mcpTools.readFile("lines.txt", 2, 100);

        assertEquals("line2\nline3", result);
    }
}
