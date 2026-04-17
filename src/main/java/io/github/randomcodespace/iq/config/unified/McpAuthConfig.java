package io.github.randomcodespace.iq.config.unified;
public record McpAuthConfig(String mode, String tokenEnv) {
    public static McpAuthConfig empty() { return new McpAuthConfig(null, null); }
}
