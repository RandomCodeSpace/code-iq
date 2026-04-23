package io.github.randomcodespace.iq.config.unified;
public record DetectorOverride(Boolean enabled) {
    public static DetectorOverride empty() { return new DetectorOverride(null); }
}
