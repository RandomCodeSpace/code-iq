package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.query.QueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the REST API controller using standalone MockMvc (no Spring context needed).
 */
@ExtendWith(MockitoExtension.class)
class GraphControllerTest {

    private MockMvc mockMvc;

    @Mock
    private QueryService queryService;

    private CodeIqConfig config;

    @BeforeEach
    void setUp() {
        config = new CodeIqConfig();
        config.setMaxDepth(10);
        config.setMaxRadius(10);
        config.setRootPath(".");
        var controller = new GraphController(queryService, config);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // --- /api/stats ---

    @Test
    void getStatsShouldReturnStats() throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("node_count", 42L);
        stats.put("edge_count", 18L);
        stats.put("nodes_by_kind", Map.of("endpoint", 10L));
        stats.put("nodes_by_layer", Map.of("backend", 30L));
        when(queryService.getStats()).thenReturn(stats);

        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.node_count").value(42))
                .andExpect(jsonPath("$.edge_count").value(18))
                .andExpect(jsonPath("$.nodes_by_kind.endpoint").value(10));
    }

    // --- /api/kinds ---

    @Test
    void listKindsShouldReturnKinds() throws Exception {
        Map<String, Object> kinds = new LinkedHashMap<>();
        kinds.put("kinds", List.of(Map.of("kind", "endpoint", "count", 5L)));
        kinds.put("total", 5);
        when(queryService.listKinds()).thenReturn(kinds);

        mockMvc.perform(get("/api/kinds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.kinds[0].kind").value("endpoint"));
    }

    // --- /api/kinds/{kind} ---

    @Test
    void nodesByKindShouldReturnPaginated() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "endpoint");
        result.put("total", 1L);
        result.put("nodes", List.of(Map.of("id", "n1", "kind", "endpoint")));
        when(queryService.nodesByKind("endpoint", 50, 0)).thenReturn(result);

        mockMvc.perform(get("/api/kinds/endpoint"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("endpoint"))
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void nodesByKindShouldAcceptPaginationParams() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "class");
        result.put("offset", 10);
        result.put("limit", 25);
        result.put("nodes", List.of());
        when(queryService.nodesByKind("class", 25, 10)).thenReturn(result);

        mockMvc.perform(get("/api/kinds/class?limit=25&offset=10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offset").value(10))
                .andExpect(jsonPath("$.limit").value(25));
    }

    @Test
    void nodesByKindShouldReturn400ForInvalidKind() throws Exception {
        mockMvc.perform(get("/api/kinds/not_a_real_kind"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void nodesByKindShouldClampNegativeOffset() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "endpoint");
        result.put("nodes", List.of());
        when(queryService.nodesByKind("endpoint", 50, 0)).thenReturn(result);

        mockMvc.perform(get("/api/kinds/endpoint?offset=-5"))
                .andExpect(status().isOk());
    }

    @Test
    void nodesByKindShouldCapLimitTo1000() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "endpoint");
        result.put("nodes", List.of());
        when(queryService.nodesByKind("endpoint", 1000, 0)).thenReturn(result);

        mockMvc.perform(get("/api/kinds/endpoint?limit=5000"))
                .andExpect(status().isOk());
    }

    // --- /api/nodes ---

    @Test
    void listNodesShouldReturnNodes() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", List.of(Map.of("id", "n1")));
        result.put("count", 1);
        when(queryService.listNodes(null, 100, 0)).thenReturn(result);

        mockMvc.perform(get("/api/nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    @Test
    void listNodesShouldFilterByKind() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", List.of());
        result.put("count", 0);
        when(queryService.listNodes("endpoint", 100, 0)).thenReturn(result);

        mockMvc.perform(get("/api/nodes?kind=endpoint"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(0));
    }

    @Test
    void listNodesShouldReturn400ForInvalidKind() throws Exception {
        mockMvc.perform(get("/api/nodes?kind=bogus_kind"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listNodesShouldClampNegativeOffset() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", List.of());
        result.put("count", 0);
        when(queryService.listNodes(null, 100, 0)).thenReturn(result);

        mockMvc.perform(get("/api/nodes?offset=-10"))
                .andExpect(status().isOk());
    }

    @Test
    void listNodesShouldCapLimitTo1000() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("nodes", List.of());
        result.put("count", 0);
        when(queryService.listNodes(null, 1000, 0)).thenReturn(result);

        mockMvc.perform(get("/api/nodes?limit=9999"))
                .andExpect(status().isOk());
    }

    // --- /api/nodes/{nodeId}/detail ---

    @Test
    void nodeDetailShouldReturnDetail() throws Exception {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", "n1");
        detail.put("kind", "endpoint");
        detail.put("outgoing_edges", List.of());
        detail.put("incoming_nodes", List.of());
        when(queryService.nodeDetailWithEdges("n1")).thenReturn(detail);

        mockMvc.perform(get("/api/nodes/n1/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("n1"));
    }

    @Test
    void nodeDetailShouldReturn404WhenNotFound() throws Exception {
        when(queryService.nodeDetailWithEdges("missing")).thenReturn(null);

        mockMvc.perform(get("/api/nodes/missing/detail"))
                .andExpect(status().isNotFound());
    }

    // --- /api/nodes/{nodeId}/neighbors ---

    @Test
    void neighborsShouldReturnNeighbors() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("node_id", "n1");
        result.put("direction", "both");
        result.put("neighbors", List.of());
        result.put("count", 0);
        when(queryService.getNeighbors("n1", "both")).thenReturn(result);

        mockMvc.perform(get("/api/nodes/n1/neighbors"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("both"));
    }

    @Test
    void neighborsShouldAcceptDirectionParam() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("direction", "out");
        result.put("neighbors", List.of());
        result.put("count", 0);
        when(queryService.getNeighbors("n1", "out")).thenReturn(result);

        mockMvc.perform(get("/api/nodes/n1/neighbors?direction=out"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction").value("out"));
    }

    // --- /api/edges ---

    @Test
    void listEdgesShouldReturnEdges() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("edges", List.of());
        result.put("count", 0);
        result.put("total", 0);
        when(queryService.listEdges(null, 100, 0)).thenReturn(result);

        mockMvc.perform(get("/api/edges"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void listEdgesShouldClampNegativeOffset() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("edges", List.of());
        result.put("count", 0);
        result.put("total", 0);
        when(queryService.listEdges(null, 100, 0)).thenReturn(result);

        mockMvc.perform(get("/api/edges?offset=-3"))
                .andExpect(status().isOk());
    }

    @Test
    void listEdgesShouldCapLimitTo1000() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("edges", List.of());
        result.put("count", 0);
        result.put("total", 0);
        when(queryService.listEdges(null, 1000, 0)).thenReturn(result);

        mockMvc.perform(get("/api/edges?limit=5000"))
                .andExpect(status().isOk());
    }

    // --- /api/ego/{center} ---

    @Test
    void egoGraphShouldReturnSubgraph() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("center", "n1");
        result.put("radius", 2);
        result.put("nodes", List.of());
        result.put("count", 0);
        when(queryService.egoGraph("n1", 2)).thenReturn(result);

        mockMvc.perform(get("/api/ego/n1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.center").value("n1"));
    }

    @Test
    void egoGraphShouldCapRadius() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("center", "n1");
        result.put("radius", 10);
        result.put("nodes", List.of());
        result.put("count", 0);
        when(queryService.egoGraph("n1", 10)).thenReturn(result);

        mockMvc.perform(get("/api/ego/n1?radius=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.radius").value(10));
    }

    // --- /api/query/cycles ---

    @Test
    void findCyclesShouldReturnCycles() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cycles", List.of(List.of("a", "b", "a")));
        result.put("count", 1);
        when(queryService.findCycles(100)).thenReturn(result);

        mockMvc.perform(get("/api/query/cycles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(1));
    }

    // --- /api/query/shortest-path ---

    @Test
    void shortestPathShouldReturnPath() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "a");
        result.put("target", "b");
        result.put("path", List.of("a", "c", "b"));
        result.put("length", 2);
        when(queryService.shortestPath("a", "b")).thenReturn(result);

        mockMvc.perform(get("/api/query/shortest-path?source=a&target=b"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length").value(2));
    }

    @Test
    void shortestPathShouldReturn404WhenNoPath() throws Exception {
        when(queryService.shortestPath("a", "b")).thenReturn(null);

        mockMvc.perform(get("/api/query/shortest-path?source=a&target=b"))
                .andExpect(status().isNotFound());
    }

    // --- /api/query/consumers/{targetId} ---

    @Test
    void consumersOfShouldReturnConsumers() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", "t1");
        result.put("consumers", List.of());
        result.put("count", 0);
        when(queryService.consumersOf("t1")).thenReturn(result);

        mockMvc.perform(get("/api/query/consumers/t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value("t1"));
    }

    // --- /api/query/producers/{targetId} ---

    @Test
    void producersOfShouldReturnProducers() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", "t1");
        result.put("producers", List.of());
        result.put("count", 0);
        when(queryService.producersOf("t1")).thenReturn(result);

        mockMvc.perform(get("/api/query/producers/t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value("t1"));
    }

    // --- /api/query/callers/{targetId} ---

    @Test
    void callersOfShouldReturnCallers() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target", "fn1");
        result.put("callers", List.of());
        result.put("count", 0);
        when(queryService.callersOf("fn1")).thenReturn(result);

        mockMvc.perform(get("/api/query/callers/fn1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.target").value("fn1"));
    }

    // --- /api/query/dependencies/{moduleId} ---

    @Test
    void dependenciesOfShouldReturnDeps() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("module", "mod1");
        result.put("dependencies", List.of());
        result.put("count", 0);
        when(queryService.dependenciesOf("mod1")).thenReturn(result);

        mockMvc.perform(get("/api/query/dependencies/mod1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.module").value("mod1"));
    }

    // --- /api/query/dependents/{moduleId} ---

    @Test
    void dependentsOfShouldReturnDependents() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("module", "mod1");
        result.put("dependents", List.of());
        result.put("count", 0);
        when(queryService.dependentsOf("mod1")).thenReturn(result);

        mockMvc.perform(get("/api/query/dependents/mod1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.module").value("mod1"));
    }

    // --- /api/triage/component ---

    @Test
    void findComponentShouldReturnComponent() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("file", "src/app.py");
        result.put("nodes", List.of());
        result.put("count", 0);
        when(queryService.findComponentByFile("src/app.py")).thenReturn(result);

        mockMvc.perform(get("/api/triage/component?file=src/app.py"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.file").value("src/app.py"));
    }

    // --- /api/triage/impact/{nodeId} ---

    @Test
    void traceImpactShouldReturnImpact() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "n1");
        result.put("depth", 3);
        result.put("impacted", List.of());
        result.put("count", 0);
        when(queryService.traceImpact("n1", 3)).thenReturn(result);

        mockMvc.perform(get("/api/triage/impact/n1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("n1"));
    }

    @Test
    void traceImpactShouldCapDepth() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("source", "n1");
        result.put("depth", 10);
        result.put("impacted", List.of());
        result.put("count", 0);
        when(queryService.traceImpact("n1", 10)).thenReturn(result);

        mockMvc.perform(get("/api/triage/impact/n1?depth=50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.depth").value(10));
    }

    // --- /api/search ---

    @Test
    void searchGraphShouldReturnResults() throws Exception {
        List<Map<String, Object>> results = List.of(
                Map.of("id", "n1", "kind", "class", "label", "UserService")
        );
        when(queryService.searchGraph("User", 50)).thenReturn(results);

        mockMvc.perform(get("/api/search?q=User"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("UserService"));
    }

    // --- /api/file ---

    @Test
    void readFileShouldReturnContent(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("hello.txt"), "Hello World", StandardCharsets.UTF_8);
        config.setRootPath(tempDir.toAbsolutePath().toString());
        var controller = new GraphController(queryService, config);
        var fileMvc = MockMvcBuilders.standaloneSetup(controller).build();

        fileMvc.perform(get("/api/file").param("path", "hello.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string("Hello World"));
    }

    @Test
    void readFileShouldReturn404ForMissing(@TempDir Path tempDir) throws Exception {
        config.setRootPath(tempDir.toAbsolutePath().toString());
        var controller = new GraphController(queryService, config);
        var fileMvc = MockMvcBuilders.standaloneSetup(controller).build();

        fileMvc.perform(get("/api/file").param("path", "nonexistent.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void readFileShouldBlockPathTraversal(@TempDir Path tempDir) throws Exception {
        config.setRootPath(tempDir.toAbsolutePath().toString());
        var controller = new GraphController(queryService, config);
        var fileMvc = MockMvcBuilders.standaloneSetup(controller).build();

        fileMvc.perform(get("/api/file").param("path", "../../../etc/passwd"))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Path traversal blocked"));
    }

    @Test
    void readFileShouldReturnLineRange(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("multi.txt"), "line1\nline2\nline3\nline4\nline5",
                StandardCharsets.UTF_8);
        config.setRootPath(tempDir.toAbsolutePath().toString());
        var controller = new GraphController(queryService, config);
        var fileMvc = MockMvcBuilders.standaloneSetup(controller).build();

        fileMvc.perform(get("/api/file")
                        .param("path", "multi.txt")
                        .param("startLine", "2")
                        .param("endLine", "4"))
                .andExpect(status().isOk())
                .andExpect(content().string("line2\nline3\nline4"));
    }

    @Test
    void readFileShouldReturnFullContentWithoutLineParams(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("full.txt"), "aaa\nbbb\nccc", StandardCharsets.UTF_8);
        config.setRootPath(tempDir.toAbsolutePath().toString());
        var controller = new GraphController(queryService, config);
        var fileMvc = MockMvcBuilders.standaloneSetup(controller).build();

        fileMvc.perform(get("/api/file").param("path", "full.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string("aaa\nbbb\nccc"));
    }

    // POST /api/analyze removed — API is read-only
}
