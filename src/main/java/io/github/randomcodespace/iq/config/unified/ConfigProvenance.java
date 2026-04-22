package io.github.randomcodespace.iq.config.unified;

public record ConfigProvenance(ConfigLayer layer, String fieldPath, Object value, String sourceLabel) {}
