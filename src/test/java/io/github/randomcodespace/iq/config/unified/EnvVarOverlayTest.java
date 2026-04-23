package io.github.randomcodespace.iq.config.unified;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class EnvVarOverlayTest {

    @Test
    void readsServingPort() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of("CODEIQ_SERVING_PORT", "9090"));
        assertEquals(9090, cfg.serving().port());
        // everything else remains null (empty overlay)
        assertNull(cfg.indexing().batchSize());
    }

    @Test
    void readsNestedMcpLimit() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_MCP_LIMITS_PERTOOLTIMEOUTMS", "30000"));
        assertEquals(30_000, cfg.mcp().limits().perToolTimeoutMs());
    }

    @Test
    void parsesBooleansAndLists() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_SERVING_READONLY", "true",
                "CODEIQ_INDEXING_LANGUAGES", "java,typescript,python"));
        assertTrue(cfg.serving().readOnly());
        assertEquals(3, cfg.indexing().languages().size());
        assertEquals("typescript", cfg.indexing().languages().get(1));
    }

    @Test
    void unknownVarIsIgnored() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_NONEXISTENT_THING", "42"));
        // No effect — don't throw, just ignore unknown keys.
        assertEquals(CodeIqUnifiedConfig.empty(), cfg);
    }

    @Test
    void nonCodeiqVarsIgnored() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "PATH", "/usr/bin",
                "HOME", "/home/x"));
        assertEquals(CodeIqUnifiedConfig.empty(), cfg);
    }

    @Test
    void malformedIntThrowsWithVarName() {
        ConfigLoadException e = assertThrows(ConfigLoadException.class,
                () -> EnvVarOverlay.from(Map.of("CODEIQ_SERVING_PORT", "not-a-port")));
        assertTrue(e.getMessage().contains("CODEIQ_SERVING_PORT"));
    }

    // ---- Phase-B extensions: detectors + parsers + Integer parallelism ------------

    @Test
    void readsDetectorsCategoriesAndInclude() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_DETECTORS_CATEGORIES", "endpoints,entities",
                "CODEIQ_DETECTORS_INCLUDE", "spring-rest-detector,jpa-entity-detector"));
        assertEquals(java.util.List.of("endpoints", "entities"), cfg.detectors().categories());
        assertEquals(java.util.List.of("spring-rest-detector", "jpa-entity-detector"),
                cfg.detectors().include());
    }

    @Test
    void readsIndexingParsersList() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_INDEXING_PARSERS", "javaparser, antlr ,regex"));
        assertEquals(java.util.List.of("javaparser", "antlr", "regex"),
                cfg.indexing().parsers());
    }

    @Test
    void readsIndexingParallelismAsInteger() {
        CodeIqUnifiedConfig cfg = EnvVarOverlay.from(Map.of(
                "CODEIQ_INDEXING_PARALLELISM", "16"));
        assertEquals(16, cfg.indexing().parallelism());
    }

    @Test
    void malformedIndexingParallelismThrows() {
        ConfigLoadException e = assertThrows(ConfigLoadException.class,
                () -> EnvVarOverlay.from(Map.of("CODEIQ_INDEXING_PARALLELISM", "many")));
        assertTrue(e.getMessage().contains("CODEIQ_INDEXING_PARALLELISM"));
    }
}
