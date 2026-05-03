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
                        ".codeiq/cache",
                        null, // parallelism — null = auto-detect (Runtime.availableProcessors())
                        500,
                        10,   // maxDepth — matches application.yml codeiq.max-depth
                        10,   // maxRadius — matches application.yml codeiq.max-radius
                        null, // maxFiles — not set in application.yml; CodeIqConfig default wins
                        null, // maxSnippetLines — not set in application.yml; CodeIqConfig default wins
                        List.of() // parsers — empty = no parser-preference override
                ),
                new ServingConfig(
                        8080,
                        "0.0.0.0",
                        false,
                        5L * 1024L * 1024L, // maxFileBytes — 5 MiB cap on /api/file + read_file
                        new Neo4jConfig(
                                ".codeiq/graph/graph.db",
                                256, 256, 1024
                        )
                ),
                new McpConfig(
                        true,
                        "http",
                        "/mcp",
                        // Default: no auth out of the box. Operators opt into
                        // bearer for production by setting mcp.auth.mode=bearer
                        // (and providing a token via CODEIQ_MCP_TOKEN env var or
                        // mcp.auth.token). Setting mode=none + allow_unauthenticated=false
                        // explicitly remains a fail-fast safety valve — see TokenResolver.
                        new McpAuthConfig("none", "CODEIQ_MCP_TOKEN", null, Boolean.TRUE),
                        new McpLimitsConfig(15_000, 500, 2_000_000L, 300, 10),
                        new McpToolsConfig(List.of("*"), List.of())
                ),
                new ObservabilityConfig(true, false, "json", "info"),
                new DetectorsConfig(List.of("default"), List.of(), List.of(), Map.of())
        );
    }
}
