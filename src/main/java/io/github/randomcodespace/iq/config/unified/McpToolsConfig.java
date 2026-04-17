package io.github.randomcodespace.iq.config.unified;
import java.util.List;
public record McpToolsConfig(List<String> enabled, List<String> disabled) {
    public static McpToolsConfig empty() { return new McpToolsConfig(List.of(), List.of()); }
}
