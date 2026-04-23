package io.github.randomcodespace.iq.config.unified;

import java.util.List;
import java.util.Map;

/**
 * In-code defaults for the unified configuration. These values match
 * the historical defaults from application.yml and picocli CLI flags,
 * so existing users see identical behavior with a zero-byte codeiq.yml.
 */
public final class ConfigDefaults {
    private ConfigDefaults() {}

    public static CodeIqUnifiedConfig builtIn() {
        return new CodeIqUnifiedConfig(
                new ProjectConfig(null, ".", null, List.of()),
                new IndexingConfig(
                        List.of(), List.of(), List.of(),
                        true,
                        ".code-iq/cache",
                        "auto",
                        500,
                        10,   // maxDepth — matches application.yml codeiq.max-depth
                        10,   // maxRadius — matches application.yml codeiq.max-radius
                        null, // maxFiles — not set in application.yml; CodeIqConfig default wins
                        null  // maxSnippetLines — not set in application.yml; CodeIqConfig default wins
                ),
                new ServingConfig(
                        8080,
                        "0.0.0.0",
                        false,
                        new Neo4jConfig(
                                ".code-iq/graph/graph.db",
                                256, 256, 1024
                        )
                ),
                new McpConfig(
                        true,
                        "http",
                        "/mcp",
                        new McpAuthConfig("none", "CODEIQ_MCP_TOKEN"),
                        new McpLimitsConfig(15_000, 500, 2_000_000L, 300),
                        new McpToolsConfig(List.of("*"), List.of())
                ),
                new ObservabilityConfig(true, false, "json", "info"),
                new DetectorsConfig(List.of("default"), Map.of())
        );
    }
}
