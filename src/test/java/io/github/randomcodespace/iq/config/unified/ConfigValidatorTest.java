package io.github.randomcodespace.iq.config.unified;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ConfigValidatorTest {

    @Test
    void builtInDefaultsAreValid() {
        List<ConfigError> errs = new ConfigValidator().validate(ConfigDefaults.builtIn());
        assertTrue(errs.isEmpty(), "defaults must be valid; got: " + errs);
    }

    @Test
    void portOutOfRangeIsRejected() {
        CodeIqUnifiedConfig bad = new CodeIqUnifiedConfig(
                ProjectConfig.empty(),
                IndexingConfig.empty(),
                new ServingConfig(99999, "0.0.0.0", false, Neo4jConfig.empty()),
                McpConfig.empty(), ObservabilityConfig.empty(), DetectorsConfig.empty());
        List<ConfigError> errs = new ConfigValidator().validate(bad);
        assertEquals(1, errs.size());
        assertEquals("serving.port", errs.get(0).fieldPath());
    }

    @Test
    void mcpTransportMustBeHttpOrStdio() {
        CodeIqUnifiedConfig bad = new CodeIqUnifiedConfig(
                ProjectConfig.empty(), IndexingConfig.empty(), ServingConfig.empty(),
                new McpConfig(true, "websocket", "/mcp", McpAuthConfig.empty(),
                        McpLimitsConfig.empty(), McpToolsConfig.empty()),
                ObservabilityConfig.empty(), DetectorsConfig.empty());
        List<ConfigError> errs = new ConfigValidator().validate(bad);
        assertTrue(errs.stream().anyMatch(e -> e.fieldPath().equals("mcp.transport")));
    }
}
