package io.github.randomcodespace.iq.config.unified;
public record McpConfig(Boolean enabled, String transport, String basePath,
                       McpAuthConfig auth, McpLimitsConfig limits, McpToolsConfig tools) {
    public static McpConfig empty() {
        return new McpConfig(null, null, null, McpAuthConfig.empty(), McpLimitsConfig.empty(), McpToolsConfig.empty());
    }
}
