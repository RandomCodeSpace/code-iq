package io.github.randomcodespace.iq.config.unified;
import java.util.List;
import java.util.Map;
public record DetectorsConfig(List<String> profiles, Map<String, DetectorOverride> overrides) {
    public static DetectorsConfig empty() { return new DetectorsConfig(List.of(), Map.of()); }
}
