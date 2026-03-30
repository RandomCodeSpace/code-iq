package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.flow.FlowModels.FlowDiagram;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for the Flow REST API controller.
 */
@ExtendWith(MockitoExtension.class)
class FlowControllerTest {

    private MockMvc mockMvc;

    @Mock
    private FlowEngine flowEngine;

    @BeforeEach
    void setUp() {
        var controller = new FlowController(java.util.Optional.of(flowEngine), new io.github.randomcodespace.iq.config.CodeIqConfig());
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void getAllFlowsReturnsAllViews() throws Exception {
        var diagram = new FlowDiagram("Test", "overview");
        Map<String, FlowDiagram> allViews = new LinkedHashMap<>();
        allViews.put("overview", diagram);
        when(flowEngine.generateAll()).thenReturn(allViews);

        mockMvc.perform(get("/api/flow"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.overview").exists())
                .andExpect(jsonPath("$.overview.view").value("overview"));
    }

    @Test
    void getFlowJsonFormat() throws Exception {
        var diagram = new FlowDiagram("Architecture Overview", "overview");
        when(flowEngine.generate("overview")).thenReturn(diagram);

        mockMvc.perform(get("/api/flow/overview"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.view").value("overview"))
                .andExpect(jsonPath("$.title").value("Architecture Overview"));
    }

    @Test
    void getFlowMermaidFormat() throws Exception {
        var diagram = new FlowDiagram("Test", "overview");
        when(flowEngine.generate("overview")).thenReturn(diagram);
        when(flowEngine.render(any(), anyString())).thenReturn("graph LR\n");

        mockMvc.perform(get("/api/flow/overview").param("format", "mermaid"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("graph LR\n"));
    }

    @Test
    void getFlowInvalidViewReturns400() throws Exception {
        when(flowEngine.generate("nonexistent")).thenThrow(
                new IllegalArgumentException("Unknown view: nonexistent"));

        mockMvc.perform(get("/api/flow/nonexistent"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getChildrenReturns404WhenNotFound() throws Exception {
        when(flowEngine.getChildren("overview", "unknown")).thenReturn(null);

        mockMvc.perform(get("/api/flow/overview/unknown/children"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getChildrenReturnsDrillDown() throws Exception {
        var childResult = new LinkedHashMap<String, Object>();
        childResult.put("drill_down_view", "ci");
        childResult.put("diagram", Map.of("view", "ci"));
        when(flowEngine.getChildren("overview", "ci_pipelines")).thenReturn(childResult);

        mockMvc.perform(get("/api/flow/overview/ci_pipelines/children"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.drill_down_view").value("ci"));
    }

    @Test
    void getParentReturns404WhenNotFound() throws Exception {
        when(flowEngine.getParentContext("unknown")).thenReturn(null);

        mockMvc.perform(get("/api/flow/overview/unknown/parent"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getParentReturnsContext() throws Exception {
        var parentResult = new LinkedHashMap<String, Object>();
        parentResult.put("parent_view", "overview");
        parentResult.put("parent_subgraph", "ci");
        parentResult.put("current_view", "ci");
        when(flowEngine.getParentContext("job_test")).thenReturn(parentResult);

        mockMvc.perform(get("/api/flow/ci/job_test/parent"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parent_view").value("overview"));
    }
}
