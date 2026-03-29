package io.github.randomcodespace.iq.web;

import io.github.randomcodespace.iq.query.QueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.view.InternalResourceViewResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the Explorer web UI controller using standalone MockMvc.
 * Validates that all routes return the correct view names and populate model attributes.
 */
@ExtendWith(MockitoExtension.class)
class ExplorerControllerTest {

    private MockMvc mockMvc;

    @Mock
    private QueryService queryService;

    @BeforeEach
    void setUp() {
        // Use a simple view resolver to avoid Thymeleaf template resolution during tests.
        var viewResolver = new InternalResourceViewResolver();
        viewResolver.setPrefix("/templates/");
        viewResolver.setSuffix(".html");

        var controller = new ExplorerController(queryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setViewResolvers(viewResolver)
                .build();
    }

    // ---- Full page routes ----

    @Test
    void indexShouldReturnExplorerIndexView() throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("node_count", 42L);
        stats.put("edge_count", 18L);
        stats.put("nodes_by_kind", Map.of("endpoint", 10L));
        stats.put("nodes_by_layer", Map.of("backend", 30L));

        Map<String, Object> kinds = new LinkedHashMap<>();
        kinds.put("kinds", List.of(Map.of("kind", "endpoint", "count", 5L)));
        kinds.put("total", 5);

        when(queryService.getStats()).thenReturn(stats);
        when(queryService.listKinds()).thenReturn(kinds);

        mockMvc.perform(get("/ui"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/index"))
                .andExpect(model().attributeExists("stats"))
                .andExpect(model().attributeExists("kinds"));
    }

    @Test
    void indexWithTrailingSlashShouldWork() throws Exception {
        when(queryService.getStats()).thenReturn(Map.of("node_count", 0L));
        when(queryService.listKinds()).thenReturn(Map.of("kinds", List.of(), "total", 0));

        mockMvc.perform(get("/ui/"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/index"));
    }

    @Test
    void nodesByKindShouldReturnNodesView() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "endpoint");
        result.put("total", 3L);
        result.put("offset", 0);
        result.put("limit", 50);
        result.put("nodes", List.of(
                Map.of("id", "ep:test:endpoint:GET /api/users", "kind", "endpoint", "label", "GET /api/users")
        ));

        when(queryService.nodesByKind("endpoint", 50, 0)).thenReturn(result);

        mockMvc.perform(get("/ui/kinds/endpoint"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/nodes"))
                .andExpect(model().attribute("kind", "endpoint"))
                .andExpect(model().attributeExists("result"));
    }

    @Test
    void nodesByKindShouldAcceptPaginationParams() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "class");
        result.put("total", 100L);
        result.put("offset", 10);
        result.put("limit", 25);
        result.put("nodes", List.of());

        when(queryService.nodesByKind("class", 25, 10)).thenReturn(result);

        mockMvc.perform(get("/ui/kinds/class?limit=25&offset=10"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/nodes"))
                .andExpect(model().attribute("kind", "class"));
    }

    @Test
    void nodeDetailShouldReturnDetailView() throws Exception {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", "cls:test:class:UserService");
        detail.put("kind", "class");
        detail.put("label", "UserService");
        detail.put("outgoing_edges", List.of());
        detail.put("incoming_nodes", List.of());

        when(queryService.nodeDetailWithEdges("cls:test:class:UserService")).thenReturn(detail);

        mockMvc.perform(get("/ui/node/cls:test:class:UserService"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/detail"))
                .andExpect(model().attributeExists("detail"));
    }

    @Test
    void nodeDetailWithNullShouldStillReturnView() throws Exception {
        when(queryService.nodeDetailWithEdges("missing")).thenReturn(null);

        mockMvc.perform(get("/ui/node/missing"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/detail"));
    }

    // ---- HTMX fragment routes ----

    @Test
    void kindsFragmentShouldReturnFragmentView() throws Exception {
        Map<String, Object> kinds = new LinkedHashMap<>();
        kinds.put("kinds", List.of(Map.of("kind", "class", "count", 10L)));
        kinds.put("total", 10);

        when(queryService.listKinds()).thenReturn(kinds);

        mockMvc.perform(get("/ui/fragments/kinds"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/fragments/kinds-grid"))
                .andExpect(model().attributeExists("kinds"));
    }

    @Test
    void nodesFragmentShouldReturnFragmentView() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "method");
        result.put("total", 5L);
        result.put("offset", 0);
        result.put("limit", 50);
        result.put("nodes", List.of());

        when(queryService.nodesByKind("method", 50, 0)).thenReturn(result);

        mockMvc.perform(get("/ui/fragments/nodes/method"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/fragments/nodes-grid"))
                .andExpect(model().attribute("kind", "method"))
                .andExpect(model().attributeExists("result"));
    }

    @Test
    void nodesFragmentShouldAcceptPagination() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", "class");
        result.put("total", 200L);
        result.put("offset", 50);
        result.put("limit", 50);
        result.put("nodes", List.of());

        when(queryService.nodesByKind("class", 50, 50)).thenReturn(result);

        mockMvc.perform(get("/ui/fragments/nodes/class?offset=50"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/fragments/nodes-grid"));
    }

    @Test
    void detailFragmentShouldReturnFragmentView() throws Exception {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("id", "n1");
        detail.put("kind", "endpoint");
        detail.put("label", "GET /health");
        detail.put("outgoing_edges", List.of());
        detail.put("incoming_nodes", List.of());

        when(queryService.nodeDetailWithEdges("n1")).thenReturn(detail);

        mockMvc.perform(get("/ui/fragments/detail/n1"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/fragments/detail-panel"))
                .andExpect(model().attributeExists("detail"));
    }

    @Test
    void searchFragmentShouldReturnSearchResultsView() throws Exception {
        List<Map<String, Object>> results = List.of(
                Map.of("id", "n1", "kind", "class", "label", "UserService")
        );

        when(queryService.searchGraph("User", 50)).thenReturn(results);

        mockMvc.perform(get("/ui/fragments/search?q=User"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/fragments/search-results"))
                .andExpect(model().attribute("query", "User"))
                .andExpect(model().attributeExists("results"));
    }

    @Test
    void searchFragmentShouldAcceptLimitParam() throws Exception {
        when(queryService.searchGraph("Repo", 10)).thenReturn(List.of());

        mockMvc.perform(get("/ui/fragments/search?q=Repo&limit=10"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/fragments/search-results"))
                .andExpect(model().attribute("query", "Repo"));
    }

    @Test
    void breadcrumbFragmentShouldReturnBreadcrumbView() throws Exception {
        mockMvc.perform(get("/ui/fragments/breadcrumb?kind=class&nodeId=n1&nodeLabel=UserService"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/fragments/breadcrumb"))
                .andExpect(model().attribute("kind", "class"))
                .andExpect(model().attribute("nodeId", "n1"))
                .andExpect(model().attribute("nodeLabel", "UserService"));
    }

    @Test
    void breadcrumbFragmentShouldWorkWithPartialParams() throws Exception {
        mockMvc.perform(get("/ui/fragments/breadcrumb?kind=endpoint"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/fragments/breadcrumb"))
                .andExpect(model().attribute("kind", "endpoint"))
                .andExpect(model().attributeDoesNotExist("nodeId"));
    }

    @Test
    void breadcrumbFragmentShouldWorkWithNoParams() throws Exception {
        mockMvc.perform(get("/ui/fragments/breadcrumb"))
                .andExpect(status().isOk())
                .andExpect(view().name("explorer/fragments/breadcrumb"));
    }
}
