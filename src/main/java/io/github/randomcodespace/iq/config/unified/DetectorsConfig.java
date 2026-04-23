package io.github.randomcodespace.iq.config.unified;
import java.util.List;
import java.util.Map;

/**
 * Detector-layer configuration.
 *
 * <ul>
 *   <li>{@code profiles} -- named detector bundles to activate.</li>
 *   <li>{@code categories} -- allow-list of detector categories (e.g. {@code ["endpoints",
 *       "entities"]}); empty means "all categories". Introduced in Phase B cleanup to
 *       give the Analyzer pipeline a unified home for filters that previously lived
 *       only on the legacy {@code .osscodeiq.yml} {@code ProjectConfig} POJO.</li>
 *   <li>{@code include} -- allow-list of detector names (by {@code Detector#getName()});
 *       empty means "no name-level filter".</li>
 *   <li>{@code overrides} -- per-detector feature flags keyed by {@code SimpleClassName}.</li>
 * </ul>
 */
public record DetectorsConfig(
        List<String> profiles,
        List<String> categories,
        List<String> include,
        Map<String, DetectorOverride> overrides) {
    public static DetectorsConfig empty() {
        return new DetectorsConfig(List.of(), List.of(), List.of(), Map.of());
    }
}
