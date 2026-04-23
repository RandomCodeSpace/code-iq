package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;

/**
 * Bridge between the new {@link CodeIqUnifiedConfig} tree and the legacy
 * {@link CodeIqConfig} bean consumed by ~100 call sites.
 *
 * <p>Copies values from the unified tree onto a fresh {@link CodeIqConfig}.
 * Fields absent from the unified tree (null) keep {@link CodeIqConfig}'s
 * in-code defaults, so behavior matches the pre-unified-config wiring even
 * when only a partial overlay is supplied.
 *
 * <p>When the call sites migrate to {@link CodeIqUnifiedConfig} directly
 * (future refactor), this adapter can be deleted.
 */
public final class UnifiedConfigAdapter {

    private UnifiedConfigAdapter() {}

    public static CodeIqConfig toCodeIqConfig(CodeIqUnifiedConfig u) {
        CodeIqConfig c = new CodeIqConfig();
        if (u == null) {
            return c;
        }

        if (u.project() != null) {
            if (u.project().root() != null) {
                c.setRootPath(u.project().root());
            }
            if (u.project().serviceName() != null) {
                c.setServiceName(u.project().serviceName());
            }
        }

        if (u.indexing() != null) {
            if (u.indexing().cacheDir() != null) {
                c.setCacheDir(u.indexing().cacheDir());
            }
            if (u.indexing().batchSize() != null) {
                c.setBatchSize(u.indexing().batchSize());
            }
            if (u.indexing().maxDepth() != null) {
                c.setMaxDepth(u.indexing().maxDepth());
            }
            if (u.indexing().maxRadius() != null) {
                c.setMaxRadius(u.indexing().maxRadius());
            }
            if (u.indexing().maxFiles() != null) {
                c.setMaxFiles(u.indexing().maxFiles());
            }
            if (u.indexing().maxSnippetLines() != null) {
                c.setMaxSnippetLines(u.indexing().maxSnippetLines());
            }
        }

        if (u.serving() != null) {
            if (u.serving().readOnly() != null) {
                c.setReadOnly(u.serving().readOnly());
            }
            if (u.serving().neo4j() != null && u.serving().neo4j().dir() != null) {
                CodeIqConfig.Graph graph = new CodeIqConfig.Graph();
                graph.setPath(u.serving().neo4j().dir());
                c.setGraph(graph);
            }
        }

        return c;
    }
}
