package io.github.randomcodespace.iq.config.unified;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConfigDefaultsTest {
    @Test
    void builtInHasKnownFieldValues() {
        CodeIqUnifiedConfig d = ConfigDefaults.builtIn();
        // These reflect values from application.yml + CLI flag defaults today.
        assertEquals(".", d.project().root());
        assertEquals(".codeiq/cache", d.indexing().cacheDir());
        assertEquals(500, d.indexing().batchSize());
        assertEquals(true, d.indexing().incremental());
        // Phase-B extension: parallelism defaults to null (= auto-detect at runtime);
        // parsers/categories/include default to empty list (= no filter).
        assertNull(d.indexing().parallelism());
        assertTrue(d.indexing().parsers().isEmpty());
        assertTrue(d.detectors().categories().isEmpty());
        assertTrue(d.detectors().include().isEmpty());
        assertEquals(8080, d.serving().port());
        assertEquals("0.0.0.0", d.serving().bindAddress());
        assertEquals(false, d.serving().readOnly());
        assertEquals(".codeiq/graph/graph.db", d.serving().neo4j().dir());
        assertEquals(true, d.mcp().enabled());
        assertEquals("http", d.mcp().transport());
        assertEquals("/mcp", d.mcp().basePath());
        assertEquals("none", d.mcp().auth().mode());
        assertEquals(15_000, d.mcp().limits().perToolTimeoutMs());
        assertEquals(500, d.mcp().limits().maxResults());
        assertEquals(2_000_000L, d.mcp().limits().maxPayloadBytes());
        assertEquals(300, d.mcp().limits().ratePerMinute());
        assertEquals(true, d.observability().metrics());
        assertEquals(false, d.observability().tracing());
        assertEquals("json", d.observability().logFormat());
        assertEquals("info", d.observability().logLevel());
    }
}
