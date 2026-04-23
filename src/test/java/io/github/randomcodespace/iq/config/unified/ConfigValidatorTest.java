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

    // ---- Phase-B extension: indexing.parallelism positivity -------------------

    @Test
    void parallelismZeroOrNegativeIsRejected() {
        CodeIqUnifiedConfig bad = new CodeIqUnifiedConfig(
                ProjectConfig.empty(),
                new IndexingConfig(
                        List.of(), List.of(), List.of(),
                        null, null, 0, null,
                        null, null, null, null,
                        List.of()),
                ServingConfig.empty(),
                McpConfig.empty(), ObservabilityConfig.empty(), DetectorsConfig.empty());
        List<ConfigError> errs = new ConfigValidator().validate(bad);
        assertTrue(errs.stream().anyMatch(e -> e.fieldPath().equals("indexing.parallelism")),
                "expected indexing.parallelism error; got " + errs);
    }

    @Test
    void parallelismNullIsValidAutoDetect() {
        // null means "auto-detect" — must NOT be flagged as an error.
        CodeIqUnifiedConfig ok = new CodeIqUnifiedConfig(
                ProjectConfig.empty(),
                IndexingConfig.empty(),
                ServingConfig.empty(),
                McpConfig.empty(), ObservabilityConfig.empty(), DetectorsConfig.empty());
        List<ConfigError> errs = new ConfigValidator().validate(ok);
        assertTrue(errs.stream().noneMatch(e -> e.fieldPath().equals("indexing.parallelism")),
                "null parallelism must be valid (auto-detect); got " + errs);
    }
}
