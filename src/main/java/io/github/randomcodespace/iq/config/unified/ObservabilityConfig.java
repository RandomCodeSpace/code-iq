package io.github.randomcodespace.iq.config.unified;
public record ObservabilityConfig(Boolean metrics, Boolean tracing, String logFormat, String logLevel) {
    public static ObservabilityConfig empty() { return new ObservabilityConfig(null, null, null, null); }
}
