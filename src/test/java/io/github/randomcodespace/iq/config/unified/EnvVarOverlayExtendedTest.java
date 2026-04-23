package io.github.randomcodespace.iq.config.unified;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Extended tests for {@link EnvVarOverlay} covering switch-case branches not
 * exercised by {@link EnvVarOverlayTest} — project metadata, indexing bounds,
 * Neo4j pools, MCP auth + tools, observability, and detectors overlays.
 */
class EnvVarOverlayExtendedTest {

    // ---------------------------------------------------------------
    // Project section
    // ---------------------------------------------------------------

    @Test
    void readsProjectName() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of("CODEIQ_PROJECT_NAME", "acme"));
        assertEquals("acme", cfg.project().name());
    }

    @Test
    void readsProjectRoot() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of("CODEIQ_PROJECT_ROOT", "/repo"));
        assertEquals("/repo", cfg.project().root());
    }

    @Test
    void readsProjectServiceName() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of("CODEIQ_PROJECT_SERVICE_NAME", "orders-api"));
        assertEquals("orders-api", cfg.project().serviceName());
    }

    // ---------------------------------------------------------------
    // Indexing bounds
    // ---------------------------------------------------------------

    @Test
    void readsIndexingBatchsize() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of("CODEIQ_INDEXING_BATCHSIZE", "250"));
        assertEquals(250, cfg.indexing().batchSize());
    }

    @Test
    void readsIndexingIncludeAndExclude() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_INDEXING_INCLUDE", "src/**/*.java",
                "CODEIQ_INDEXING_EXCLUDE", "**/target/**,**/build/**"));
        assertEquals(List.of("src/**/*.java"), cfg.indexing().include());
        assertEquals(List.of("**/target/**", "**/build/**"), cfg.indexing().exclude());
    }

    @Test
    void readsIndexingIncremental() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of("CODEIQ_INDEXING_INCREMENTAL", "false"));
        assertEquals(Boolean.FALSE, cfg.indexing().incremental());
    }

    @Test
    void readsIndexingCacheDir() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of("CODEIQ_INDEXING_CACHEDIR", ".cache/intel"));
        assertEquals(".cache/intel", cfg.indexing().cacheDir());
    }

    @Test
    void readsIndexingMaxDepthRadiusFilesSnippet() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_INDEXING_MAX_DEPTH", "5",
                "CODEIQ_INDEXING_MAX_RADIUS", "3",
                "CODEIQ_INDEXING_MAX_FILES", "10000",
                "CODEIQ_INDEXING_MAX_SNIPPET_LINES", "40"));
        assertEquals(5, cfg.indexing().maxDepth());
        assertEquals(3, cfg.indexing().maxRadius());
        assertEquals(10000, cfg.indexing().maxFiles());
        assertEquals(40, cfg.indexing().maxSnippetLines());
    }

    @Test
    void malformedIndexingBatchsizeThrowsWithVarName() {
        ConfigLoadException e = assertThrows(ConfigLoadException.class,
                () -> EnvVarOverlay.from(Map.of("CODEIQ_INDEXING_BATCHSIZE", "oops")));
        assertTrue(e.getMessage().contains("CODEIQ_INDEXING_BATCHSIZE"));
    }

    // ---------------------------------------------------------------
    // Serving: bind addr + Neo4j pools
    // ---------------------------------------------------------------

    @Test
    void readsServingBindAddress() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of("CODEIQ_SERVING_BINDADDRESS", "127.0.0.1"));
        assertEquals("127.0.0.1", cfg.serving().bindAddress());
    }

    @Test
    void readsServingNeo4jDir() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of("CODEIQ_SERVING_NEO4J_DIR", "/var/neo4j"));
        assertEquals("/var/neo4j", cfg.serving().neo4j().dir());
    }

    @Test
    void readsServingNeo4jPageCacheAndHeapSizes() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_SERVING_NEO4J_PAGECACHEMB", "512",
                "CODEIQ_SERVING_NEO4J_HEAPINITIALMB", "256",
                "CODEIQ_SERVING_NEO4J_HEAPMAXMB", "1024"));
        assertEquals(512, cfg.serving().neo4j().pageCacheMb());
        assertEquals(256, cfg.serving().neo4j().heapInitialMb());
        assertEquals(1024, cfg.serving().neo4j().heapMaxMb());
    }

    @Test
    void malformedPageCacheThrowsWithVarName() {
        ConfigLoadException e = assertThrows(ConfigLoadException.class,
                () -> EnvVarOverlay.from(Map.of("CODEIQ_SERVING_NEO4J_PAGECACHEMB", "huge")));
        assertTrue(e.getMessage().contains("CODEIQ_SERVING_NEO4J_PAGECACHEMB"));
    }

    // ---------------------------------------------------------------
    // MCP: enabled + transport + basepath + auth + limits + tools
    // ---------------------------------------------------------------

    @Test
    void readsMcpEnabledAndTransport() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_MCP_ENABLED", "true",
                "CODEIQ_MCP_TRANSPORT", "http"));
        assertEquals(Boolean.TRUE, cfg.mcp().enabled());
        assertEquals("http", cfg.mcp().transport());
    }

    @Test
    void readsMcpBasePath() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of("CODEIQ_MCP_BASEPATH", "/mcp"));
        assertEquals("/mcp", cfg.mcp().basePath());
    }

    @Test
    void readsMcpAuthModeAndTokenEnv() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_MCP_AUTH_MODE", "bearer",
                "CODEIQ_MCP_AUTH_TOKENENV", "MCP_TOKEN"));
        assertEquals("bearer", cfg.mcp().auth().mode());
        assertEquals("MCP_TOKEN", cfg.mcp().auth().tokenEnv());
    }

    @Test
    void readsMcpLimitsAllFields() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_MCP_LIMITS_PERTOOLTIMEOUTMS", "5000",
                "CODEIQ_MCP_LIMITS_MAXRESULTS", "200",
                "CODEIQ_MCP_LIMITS_MAXPAYLOADBYTES", "1048576",
                "CODEIQ_MCP_LIMITS_RATEPERMINUTE", "60"));
        assertEquals(5000, cfg.mcp().limits().perToolTimeoutMs());
        assertEquals(200, cfg.mcp().limits().maxResults());
        assertEquals(1_048_576L, cfg.mcp().limits().maxPayloadBytes());
        assertEquals(60, cfg.mcp().limits().ratePerMinute());
    }

    @Test
    void malformedMaxPayloadBytesThrowsWithVarName() {
        ConfigLoadException e = assertThrows(ConfigLoadException.class,
                () -> EnvVarOverlay.from(Map.of("CODEIQ_MCP_LIMITS_MAXPAYLOADBYTES", "big")));
        assertTrue(e.getMessage().contains("CODEIQ_MCP_LIMITS_MAXPAYLOADBYTES"));
    }

    @Test
    void readsMcpToolsEnabledAndDisabled() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_MCP_TOOLS_ENABLED", "get_stats,find_consumers",
                "CODEIQ_MCP_TOOLS_DISABLED", "run_cypher"));
        assertEquals(List.of("get_stats", "find_consumers"), cfg.mcp().tools().enabled());
        assertEquals(List.of("run_cypher"), cfg.mcp().tools().disabled());
    }

    // ---------------------------------------------------------------
    // Observability
    // ---------------------------------------------------------------

    @Test
    void readsObservabilityMetricsTracingLogFormatLogLevel() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_OBSERVABILITY_METRICS", "true",
                "CODEIQ_OBSERVABILITY_TRACING", "false",
                "CODEIQ_OBSERVABILITY_LOGFORMAT", "json",
                "CODEIQ_OBSERVABILITY_LOGLEVEL", "DEBUG"));
        assertEquals(Boolean.TRUE, cfg.observability().metrics());
        assertEquals(Boolean.FALSE, cfg.observability().tracing());
        assertEquals("json", cfg.observability().logFormat());
        assertEquals("DEBUG", cfg.observability().logLevel());
    }

    // ---------------------------------------------------------------
    // Detectors profiles (alongside pre-existing categories/include)
    // ---------------------------------------------------------------

    @Test
    void readsDetectorsProfiles() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_DETECTORS_PROFILES", "backend,api"));
        assertEquals(List.of("backend", "api"), cfg.detectors().profiles());
    }

    // ---------------------------------------------------------------
    // splitCsv edge cases
    // ---------------------------------------------------------------

    @Test
    void emptyCsvResultsInEmptyList() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of("CODEIQ_INDEXING_LANGUAGES", ""));
        assertThat(cfg.indexing().languages()).isEmpty();
    }

    @Test
    void csvWithWhitespaceOnlyEntries_areSkipped() {
        // "   , ," → three whitespace-only tokens that must be filtered out
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_INDEXING_LANGUAGES", "   , ,"));
        assertThat(cfg.indexing().languages()).isEmpty();
    }

    @Test
    void csvTrimsWhitespaceFromEntries() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_MCP_TOOLS_ENABLED", "  get_stats ,   find_cycles  "));
        assertEquals(List.of("get_stats", "find_cycles"), cfg.mcp().tools().enabled());
    }

    // ---------------------------------------------------------------
    // Unknown variables under the CODEIQ_ prefix must also be ignored
    // (exercises the default switch arm)
    // ---------------------------------------------------------------

    @Test
    void unknownCodeiqSubKey_isIgnored() {
        // CODEIQ_FUTURE_FEATURE doesn't match any case → no effect
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_FUTURE_FEATURE", "yes",
                "CODEIQ_SERVING_PORT", "9999"));
        assertEquals(9999, cfg.serving().port());
    }

    // ---------------------------------------------------------------
    // Determinism: same env map, multiple invocations produce equal config
    // ---------------------------------------------------------------

    @Test
    void deterministic_sameEnvProducesEqualConfig() {
        var env = new LinkedHashMap<String, String>();
        env.put("CODEIQ_SERVING_PORT", "8080");
        env.put("CODEIQ_INDEXING_LANGUAGES", "java,python");
        env.put("CODEIQ_DETECTORS_CATEGORIES", "endpoints");
        var run1 = EnvVarOverlay.from(env);
        var run2 = EnvVarOverlay.from(env);
        assertEquals(run1, run2);
    }
}
