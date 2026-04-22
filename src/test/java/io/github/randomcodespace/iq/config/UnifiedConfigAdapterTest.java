package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import io.github.randomcodespace.iq.config.unified.ConfigDefaults;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnifiedConfigAdapterTest {

    @Test
    void adapterProjectsUnifiedValuesIntoLegacyApi() {
        CodeIqUnifiedConfig u = ConfigDefaults.builtIn();
        CodeIqConfig legacy = UnifiedConfigAdapter.adapt(u);

        assertEquals(".", legacy.getRootPath());
        assertEquals(".code-iq/cache", legacy.getCacheDir());
        assertEquals(".code-iq/graph/graph.db", legacy.getGraph().getPath());
        assertEquals(500, legacy.getBatchSize());
        assertFalse(legacy.isReadOnly());
    }
}
