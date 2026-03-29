package io.github.randomcodespace.iq.analyzer;

import java.nio.file.Path;

/**
 * A file discovered during repository scanning.
 *
 * @param path      path relative to the repository root
 * @param language  language identifier derived from extension/filename
 * @param sizeBytes file size in bytes
 */
public record DiscoveredFile(
        Path path,
        String language,
        long sizeBytes
) {}
