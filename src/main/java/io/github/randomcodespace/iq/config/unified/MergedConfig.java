package io.github.randomcodespace.iq.config.unified;

import java.util.Map;

public record MergedConfig(CodeIqUnifiedConfig effective, Map<String, ConfigProvenance> provenance) {}
