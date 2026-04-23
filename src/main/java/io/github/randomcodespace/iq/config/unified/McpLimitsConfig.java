package io.github.randomcodespace.iq.config.unified;
public record McpLimitsConfig(Integer perToolTimeoutMs, Integer maxResults,
                             Long maxPayloadBytes, Integer ratePerMinute) {
    public static McpLimitsConfig empty() { return new McpLimitsConfig(null, null, null, null); }
}
