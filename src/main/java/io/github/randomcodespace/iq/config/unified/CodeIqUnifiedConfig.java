package io.github.randomcodespace.iq.config.unified;

/**
 * Root of the unified configuration tree for codeiq. All sections are
 * non-null; absent sections in a YAML source become their in-code defaults
 * (see ConfigDefaults). Records are immutable — apply overlays by building
 * a new instance via ConfigMerger.
 */
public record CodeIqUnifiedConfig(
        ProjectConfig project,
        IndexingConfig indexing,
        ServingConfig serving,
        McpConfig mcp,
        ObservabilityConfig observability,
        DetectorsConfig detectors
) {
    /** Returns an instance with all sections at their empty defaults. */
    public static CodeIqUnifiedConfig empty() {
        return new CodeIqUnifiedConfig(
                ProjectConfig.empty(),
                IndexingConfig.empty(),
                ServingConfig.empty(),
                McpConfig.empty(),
                ObservabilityConfig.empty(),
                DetectorsConfig.empty()
        );
    }
}
