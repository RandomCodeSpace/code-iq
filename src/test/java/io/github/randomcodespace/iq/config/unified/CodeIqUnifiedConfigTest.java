package io.github.randomcodespace.iq.config.unified;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CodeIqUnifiedConfigTest {
    @Test
    void defaultsInstanceHasAllSectionsNonNull() {
        CodeIqUnifiedConfig cfg = CodeIqUnifiedConfig.empty();
        assertNotNull(cfg.project());
        assertNotNull(cfg.indexing());
        assertNotNull(cfg.serving());
        assertNotNull(cfg.mcp());
        assertNotNull(cfg.observability());
        assertNotNull(cfg.detectors());
    }
}
