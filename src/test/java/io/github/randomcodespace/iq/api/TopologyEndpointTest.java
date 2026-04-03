package io.github.randomcodespace.iq.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.randomcodespace.iq.analyzer.Analyzer;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.mcp.McpTools;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.query.QueryService;
import io.github.randomcodespace.iq.query.StatsService;
import io.github.randomcodespace.iq.query.TopologyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.neo4j.graphdb.GraphDatabaseService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the /api/topology endpoint and related components.
 */
@ExtendWith(MockitoExtension.class)
class TopologyEndpointTest {

    @Mock
    private QueryService queryService;

    @Mock
    private Analyzer analyzer;

    @Mock
    private GraphStore graphStore;

    @Mock
    private GraphDatabaseService graphDb;

    @Mock
    private TopologyService topologyService;

    private CodeIqConfig config;
    private MockMvc mockMvc;
    private McpTools mcpTools;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        config = new CodeIqConfig();
        config.setMaxDepth(10);
        config.setMaxRadius(10);
        config.setRootPath(".");

        objectMapper = new ObjectMapper();

        // GraphStore returns >0 count so TopologyController loads from Neo4j
        lenient().when(graphStore.count()).thenReturn(1L);
        lenient().when(graphStore.findAll()).thenReturn(List.of());

        var topologyController = new TopologyController(topologyService, graphStore, config);
        mockMvc = MockMvcBuilders.standaloneSetup(topologyController).build();

        mcpTools = new McpTools(queryService, config, objectMapper,
                Optional.empty(), graphDb, new StatsService(),
                new TopologyService(), graphStore,
                Optional.empty(), Optional.empty());
    }

    private Map<String, Object> buildTopologyResponse() {
        Map<String, Object> svc = new LinkedHashMap<>();
        svc.put("id", "order-service");
        svc.put("label", "OrderService");
        svc.put("kind", "service");
        svc.put("layer", "backend");
        svc.put("node_count", 45L);

        Map<String, Object> infra = new LinkedHashMap<>();
        infra.put("id", "postgresql:orders-db");
        infra.put("label", "orders-db");
        infra.put("kind", "database_connection");
        infra.put("type", "postgresql");

        Map<String, Object> conn = new LinkedHashMap<>();
        conn.put("source", "order-service");
        conn.put("target", "postgresql:orders-db");
        conn.put("kind", "queries");
        conn.put("count", 12L);

        Map<String, Object> topology = new LinkedHashMap<>();
        topology.put("services", List.of(svc));
        topology.put("infrastructure", List.of(infra));
        topology.put("connections", List.of(conn));
        return topology;
    }

    // --- GET /api/topology ---

    @Test
    void getTopologyShouldReturnTopologyResponse() throws Exception {
        when(topologyService.getTopology(anyList(), anyList())).thenReturn(buildTopologyResponse());

        mockMvc.perform(get("/api/topology"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.services").isArray())
                .andExpect(jsonPath("$.infrastructure").isArray())
                .andExpect(jsonPath("$.connections").isArray());
    }

    @Test
    void getTopologyShouldReturnServiceDetails() throws Exception {
        when(topologyService.getTopology(anyList(), anyList())).thenReturn(buildTopologyResponse());

        mockMvc.perform(get("/api/topology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services[0].id").value("order-service"))
                .andExpect(jsonPath("$.services[0].kind").value("service"))
                .andExpect(jsonPath("$.services[0].layer").value("backend"))
                .andExpect(jsonPath("$.services[0].node_count").value(45));
    }

    @Test
    void getTopologyShouldReturnInfraDetails() throws Exception {
        when(topologyService.getTopology(anyList(), anyList())).thenReturn(buildTopologyResponse());

        mockMvc.perform(get("/api/topology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.infrastructure[0].id").value("postgresql:orders-db"))
                .andExpect(jsonPath("$.infrastructure[0].kind").value("database_connection"))
                .andExpect(jsonPath("$.infrastructure[0].type").value("postgresql"));
    }

    @Test
    void getTopologyShouldReturnConnectionDetails() throws Exception {
        when(topologyService.getTopology(anyList(), anyList())).thenReturn(buildTopologyResponse());

        mockMvc.perform(get("/api/topology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.connections[0].source").value("order-service"))
                .andExpect(jsonPath("$.connections[0].target").value("postgresql:orders-db"))
                .andExpect(jsonPath("$.connections[0].kind").value("queries"))
                .andExpect(jsonPath("$.connections[0].count").value(12));
    }

    @Test
    void getTopologyDelegatesToTopologyService() throws Exception {
        when(topologyService.getTopology(anyList(), anyList())).thenReturn(buildTopologyResponse());

        mockMvc.perform(get("/api/topology")).andExpect(status().isOk());

        verify(topologyService).getTopology(anyList(), anyList());
    }

    @Test
    void getTopologyReturnsEmptyListsWhenNoData() throws Exception {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("services", List.of());
        empty.put("infrastructure", List.of());
        empty.put("connections", List.of());
        when(topologyService.getTopology(anyList(), anyList())).thenReturn(empty);

        mockMvc.perform(get("/api/topology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.services").isEmpty())
                .andExpect(jsonPath("$.infrastructure").isEmpty())
                .andExpect(jsonPath("$.connections").isEmpty());
    }

    // --- QueryService.getTopology() ---

    @Test
    void queryServiceGetTopologyDelegatesToGraphStore() {
        CodeIqConfig cfg = new CodeIqConfig();
        QueryService service = new QueryService(graphStore, cfg);
        when(graphStore.getTopology()).thenReturn(buildTopologyResponse());

        Map<String, Object> result = service.getTopology();

        assertNotNull(result);
        assertTrue(result.containsKey("services"));
        assertTrue(result.containsKey("infrastructure"));
        assertTrue(result.containsKey("connections"));
        verify(graphStore).getTopology();
    }

    @Test
    void queryServiceGetTopologyReturnsServicesInfraConnections() {
        CodeIqConfig cfg = new CodeIqConfig();
        QueryService service = new QueryService(graphStore, cfg);
        Map<String, Object> topology = buildTopologyResponse();
        when(graphStore.getTopology()).thenReturn(topology);

        Map<String, Object> result = service.getTopology();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> services = (List<Map<String, Object>>) result.get("services");
        assertEquals(1, services.size());
        assertEquals("order-service", services.getFirst().get("id"));
    }

    // --- McpTools get_topology ---

    @Test
    void mcpGetTopologyShouldReturnJsonWithAllKeys() throws Exception {
        when(queryService.getTopology()).thenReturn(buildTopologyResponse());

        String json = mcpTools.getTopology();
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

        assertNotNull(parsed.get("services"));
        assertNotNull(parsed.get("infrastructure"));
        assertNotNull(parsed.get("connections"));
        verify(queryService).getTopology();
    }

    @Test
    void mcpGetTopologyShouldHandleException() throws Exception {
        when(queryService.getTopology()).thenThrow(new RuntimeException("Neo4j unavailable"));

        String json = mcpTools.getTopology();
        Map<String, Object> parsed = objectMapper.readValue(json, new TypeReference<>() {});

        assertNotNull(parsed.get("error"));
    }
}
