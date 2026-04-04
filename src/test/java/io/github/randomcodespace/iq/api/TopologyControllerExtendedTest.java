package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import io.github.randomcodespace.iq.query.TopologyService;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Extended tests for TopologyController that exercise the actual REST endpoints
 * using Spring MockMvc in standalone mode with mocked TopologyService and GraphStore.
 *
 * <p>The controller loads data from Neo4j (via GraphStore) when available.
 * We wire a mock GraphStore that reports data exists and returns node lists,
 * which causes the controller to skip the H2 fallback path.</p>
 */
@ExtendWith(MockitoExtension.class)
class TopologyControllerExtendedTest {

    private MockMvc mockMvc;

    @Mock
    private TopologyService topologyService;

    @Mock
    private GraphStore graphStore;

    private TopologyController controller;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        var config = new CodeIqConfig();
        // Use the temp dir as rootPath so H2 fallback finds no cache file
        config.setRootPath(tempDir.toString());
        controller = new TopologyController(topologyService, graphStore, config);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    /** Prime the mock GraphStore so the controller loads data from Neo4j. */
    private void primeGraphStore(List<CodeNode> nodes) {
        when(graphStore.count()).thenReturn((long) nodes.size());
        when(graphStore.findAll()).thenReturn(nodes);
    }

    private CodeNode makeServiceNode(String name) {
        CodeNode n = new CodeNode();
        n.setId("svc:" + name);
        n.setLabel(name);
        n.setKind(NodeKind.SERVICE);
        n.setProperties(new HashMap<>());
        n.setEdges(new ArrayList<>());
        return n;
    }

    // ---- GET /api/topology ------------------------------------------

    @Test
    void getTopologyReturns200WithServicesAndConnections() throws Exception {
        var node = makeServiceNode("api-service");
        primeGraphStore(List.of(node));

        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("services", List.of(Map.of("name", "api-service")));
        expected.put("connections", List.of());

        when(topologyService.getTopology(anyList(), anyList())).thenReturn(expected);

        mockMvc.perform(get("/api/topology").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services").isArray())
                .andExpect(jsonPath("$.connections").isArray());
    }

    // ---- GET /api/topology when no data → 404 ----------------------

    @Test
    void getTopologyReturns404WhenNoCacheAndNoNeo4j() throws Exception {
        // graphStore returns 0 nodes → hasNeo4jData = false
        // config points to non-existent H2 file → requireCache throws
        when(graphStore.count()).thenReturn(0L);

        mockMvc.perform(get("/api/topology").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    // ---- GET /api/topology/services/{name} -------------------------

    @Test
    void serviceDetailReturns200() throws Exception {
        var node = makeServiceNode("auth-service");
        primeGraphStore(List.of(node));

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("name", "auth-service");
        detail.put("endpoint_count", 5);
        detail.put("entity_count", 2);

        when(topologyService.serviceDetail(eq("auth-service"), anyList(), anyList())).thenReturn(detail);

        mockMvc.perform(get("/api/topology/services/auth-service")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("auth-service"))
                .andExpect(jsonPath("$.endpoint_count").value(5));
    }

    // ---- GET /api/topology/services/{name}/deps --------------------

    @Test
    void serviceDependenciesReturns200() throws Exception {
        var node = makeServiceNode("order-service");
        primeGraphStore(List.of(node));

        Map<String, Object> deps = new LinkedHashMap<>();
        deps.put("service", "order-service");
        deps.put("count", 2);
        deps.put("dependencies", List.of(
                Map.of("name", "payment-service", "type", "CALLS")
        ));

        when(topologyService.serviceDependencies(eq("order-service"), anyList(), anyList())).thenReturn(deps);

        mockMvc.perform(get("/api/topology/services/order-service/deps")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("order-service"))
                .andExpect(jsonPath("$.count").value(2));
    }

    // ---- GET /api/topology/services/{name}/dependents --------------

    @Test
    void serviceDependentsReturns200() throws Exception {
        var node = makeServiceNode("payment-service");
        primeGraphStore(List.of(node));

        Map<String, Object> dependents = new LinkedHashMap<>();
        dependents.put("service", "payment-service");
        dependents.put("dependents", List.of());

        when(topologyService.serviceDependents(eq("payment-service"), anyList(), anyList()))
                .thenReturn(dependents);

        mockMvc.perform(get("/api/topology/services/payment-service/dependents")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("payment-service"));
    }

    // ---- GET /api/topology/blast-radius/{nodeId} -------------------

    @Test
    void blastRadiusReturns200() throws Exception {
        var node = makeServiceNode("inventory-service");
        primeGraphStore(List.of(node));

        Map<String, Object> blast = new LinkedHashMap<>();
        blast.put("source", "svc:inventory-service");
        blast.put("impacted_nodes", 3);
        blast.put("affected", List.of());

        when(topologyService.blastRadius(eq("svc:inventory-service"), anyList(), anyList()))
                .thenReturn(blast);

        mockMvc.perform(get("/api/topology/blast-radius/svc:inventory-service")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("svc:inventory-service"));
    }

    // ---- GET /api/topology/path ------------------------------------

    @Test
    void findPathReturns200WithEmptyList() throws Exception {
        var node = makeServiceNode("svc-a");
        primeGraphStore(List.of(node));

        when(topologyService.findPath(eq("svc-a"), eq("svc-b"), anyList(), anyList()))
                .thenReturn(List.of());

        mockMvc.perform(get("/api/topology/path")
                        .param("from", "svc-a")
                        .param("to", "svc-b")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void findPathReturns200WithPath() throws Exception {
        var node = makeServiceNode("svc-a");
        primeGraphStore(List.of(node));

        List<Map<String, Object>> path = List.of(
                Map.of("id", "svc-a", "label", "Service A"),
                Map.of("id", "svc-b", "label", "Service B")
        );
        when(topologyService.findPath(eq("svc-a"), eq("svc-b"), anyList(), anyList()))
                .thenReturn(path);

        mockMvc.perform(get("/api/topology/path")
                        .param("from", "svc-a")
                        .param("to", "svc-b")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(2));
    }

    // ---- GET /api/topology/bottlenecks ----------------------------

    @Test
    void findBottlenecksReturns200() throws Exception {
        var node = makeServiceNode("gateway");
        primeGraphStore(List.of(node));

        List<Map<String, Object>> bottlenecks = List.of(
                Map.of("name", "gateway", "in_degree", 10, "out_degree", 5)
        );
        when(topologyService.findBottlenecks(anyList(), anyList())).thenReturn(bottlenecks);

        mockMvc.perform(get("/api/topology/bottlenecks").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("gateway"));
    }

    @Test
    void findBottlenecksReturns200WhenEmpty() throws Exception {
        var node = makeServiceNode("solo-service");
        primeGraphStore(List.of(node));

        when(topologyService.findBottlenecks(anyList(), anyList())).thenReturn(List.of());

        mockMvc.perform(get("/api/topology/bottlenecks").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ---- GET /api/topology/circular --------------------------------

    @Test
    void findCircularDepsReturns200() throws Exception {
        var node = makeServiceNode("svc-a");
        primeGraphStore(List.of(node));

        List<List<String>> cycles = List.of(List.of("svc-a", "svc-b", "svc-a"));
        when(topologyService.findCircularDeps(anyList(), anyList())).thenReturn(cycles);

        mockMvc.perform(get("/api/topology/circular").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0]").isArray());
    }

    @Test
    void findCircularDepsReturnsEmptyWhenNoCycles() throws Exception {
        var node = makeServiceNode("standalone");
        primeGraphStore(List.of(node));

        when(topologyService.findCircularDeps(anyList(), anyList())).thenReturn(List.of());

        mockMvc.perform(get("/api/topology/circular").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ---- GET /api/topology/dead -----------------------------------

    @Test
    void findDeadServicesReturns200() throws Exception {
        var node = makeServiceNode("orphan-service");
        primeGraphStore(List.of(node));

        List<Map<String, Object>> dead = List.of(Map.of("name", "orphan-service"));
        when(topologyService.findDeadServices(anyList(), anyList())).thenReturn(dead);

        mockMvc.perform(get("/api/topology/dead").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].name").value("orphan-service"));
    }

    @Test
    void findDeadServicesReturnsEmptyWhenAllConnected() throws Exception {
        var node = makeServiceNode("connected-service");
        primeGraphStore(List.of(node));

        when(topologyService.findDeadServices(anyList(), anyList())).thenReturn(List.of());

        mockMvc.perform(get("/api/topology/dead").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isEmpty());
    }

    // ---- invalidateCache() -----------------------------------------

    @Test
    void invalidateCacheClearsState() throws Exception {
        var node = makeServiceNode("service-x");
        primeGraphStore(List.of(node));

        Map<String, Object> topo = Map.of("services", List.of(), "connections", List.of());
        when(topologyService.getTopology(anyList(), anyList())).thenReturn(topo);

        // First call loads data
        mockMvc.perform(get("/api/topology").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // Invalidate
        controller.invalidateCache();

        // Second call should reload data (count called again)
        mockMvc.perform(get("/api/topology").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        // graphStore.count() should have been called at least twice (once per load)
        verify(graphStore, atLeast(2)).count();
    }

    // ---- GraphStore throws → falls back to H2 → 404 ---------------

    @Test
    void graphStoreExceptionFallsBackToH2AndReturns404() throws Exception {
        // graphStore.count() throws → hasNeo4jData = false → H2 fallback
        // H2 file doesn't exist → requireCache() throws → 404
        when(graphStore.count()).thenThrow(new RuntimeException("neo4j down"));

        mockMvc.perform(get("/api/topology").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }
}
