package io.github.randomcodespace.iq.web;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests the routing logic of {@link SpaController} using standalone MockMvc.
 * Verifies that explicit SPA routes and catch-all paths forward to /index.html.
 */
class SpaControllerMockMvcTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SpaController()).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/graph",
            "/explorer",
            "/console",
            "/api-docs",
            "/dashboard"
    })
    void explicitRoutesForwardToIndexHtml(String path) throws Exception {
        mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void graphWildcardForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/graph/some-subpath"))
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void explorerWildcardForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/explorer/class/UserService"))
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void dashboardWildcardForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/dashboard/overview"))
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void consoleWildcardForwardsToIndexHtml() throws Exception {
        mockMvc.perform(get("/console/query"))
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void catchAllHandlerForwardsSingleSegmentPaths() throws Exception {
        mockMvc.perform(get("/settings"))
                .andExpect(forwardedUrl("/index.html"));
    }

    @Test
    void catchAllHandlerForwardsOtherSingleSegmentPaths() throws Exception {
        mockMvc.perform(get("/topology"))
                .andExpect(forwardedUrl("/index.html"));
    }
}
