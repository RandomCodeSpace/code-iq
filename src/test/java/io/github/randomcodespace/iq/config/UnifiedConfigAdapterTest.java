package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import io.github.randomcodespace.iq.config.unified.ConfigDefaults;
import io.github.randomcodespace.iq.config.unified.IndexingConfig;
import io.github.randomcodespace.iq.config.unified.McpAuthConfig;
import io.github.randomcodespace.iq.config.unified.McpConfig;
import io.github.randomcodespace.iq.config.unified.McpLimitsConfig;
import io.github.randomcodespace.iq.config.unified.McpToolsConfig;
import io.github.randomcodespace.iq.config.unified.ObservabilityConfig;
import io.github.randomcodespace.iq.config.unified.DetectorsConfig;
import io.github.randomcodespace.iq.config.unified.ProjectConfig;
import io.github.randomcodespace.iq.config.unified.ServingConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UnifiedConfigAdapterTest {

    @Test
    void adapterProjectsUnifiedValuesIntoLegacyApi() {
        CodeIqUnifiedConfig u = ConfigDefaults.builtIn();
        CodeIqConfig legacy = UnifiedConfigAdapter.toCodeIqConfig(u);

        assertEquals(".", legacy.getRootPath());
        assertEquals(".code-iq/cache", legacy.getCacheDir());
        assertEquals(".code-iq/graph/graph.db", legacy.getGraph().getPath());
        assertEquals(500, legacy.getBatchSize());
        assertFalse(legacy.isReadOnly());
        // maxDepth and maxRadius flow through builtIn() matching application.yml
        assertEquals(10, legacy.getMaxDepth());
        assertEquals(10, legacy.getMaxRadius());
    }

    @Test
    void nullOverlayReturnsLegacyDefaults() {
        CodeIqConfig legacy = UnifiedConfigAdapter.toCodeIqConfig(null);
        CodeIqConfig baseline = new CodeIqConfig();

        assertEquals(baseline.getRootPath(), legacy.getRootPath());
        assertEquals(baseline.getCacheDir(), legacy.getCacheDir());
        assertEquals(baseline.getBatchSize(), legacy.getBatchSize());
        assertEquals(baseline.getMaxDepth(), legacy.getMaxDepth());
        assertEquals(baseline.getMaxRadius(), legacy.getMaxRadius());
        assertEquals(baseline.getMaxFiles(), legacy.getMaxFiles());
        assertEquals(baseline.getMaxSnippetLines(), legacy.getMaxSnippetLines());
        assertEquals(baseline.isReadOnly(), legacy.isReadOnly());
        assertNull(legacy.getServiceName());
        assertEquals(baseline.getGraph().getPath(), legacy.getGraph().getPath());
    }

    @Test
    void emptyOverlayPreservesLegacyDefaults() {
        // empty() is distinct from builtIn() — every scalar is null. The
        // adapter must leave CodeIqConfig's in-code defaults untouched.
        CodeIqConfig legacy = UnifiedConfigAdapter.toCodeIqConfig(CodeIqUnifiedConfig.empty());
        CodeIqConfig baseline = new CodeIqConfig();

        assertEquals(baseline.getRootPath(), legacy.getRootPath());
        assertEquals(baseline.getCacheDir(), legacy.getCacheDir());
        assertEquals(baseline.getBatchSize(), legacy.getBatchSize());
        // empty() doesn't set maxDepth/maxRadius, so CodeIqConfig's own default is 10
        assertEquals(baseline.getMaxDepth(), legacy.getMaxDepth());
        assertEquals(baseline.getMaxRadius(), legacy.getMaxRadius());
        assertEquals(baseline.getMaxFiles(), legacy.getMaxFiles());
        assertEquals(baseline.getMaxSnippetLines(), legacy.getMaxSnippetLines());
        assertFalse(legacy.isReadOnly());
        assertNull(legacy.getServiceName());
        assertEquals(baseline.getGraph().getPath(), legacy.getGraph().getPath());
    }

    @Test
    void partialOverlayOnlyOverridesSetFields() {
        CodeIqUnifiedConfig u = new CodeIqUnifiedConfig(
                new ProjectConfig(null, "/custom", null, List.of()),
                IndexingConfig.empty(),
                ServingConfig.empty(),
                new McpConfig(null, null, null,
                        McpAuthConfig.empty(),
                        McpLimitsConfig.empty(),
                        McpToolsConfig.empty()),
                ObservabilityConfig.empty(),
                DetectorsConfig.empty()
        );

        CodeIqConfig legacy = UnifiedConfigAdapter.toCodeIqConfig(u);
        CodeIqConfig baseline = new CodeIqConfig();

        assertEquals("/custom", legacy.getRootPath());
        // All other fields remain at CodeIqConfig's in-code defaults
        assertEquals(baseline.getCacheDir(), legacy.getCacheDir());
        assertEquals(baseline.getBatchSize(), legacy.getBatchSize());
        assertEquals(baseline.getMaxDepth(), legacy.getMaxDepth());
        assertEquals(baseline.getMaxRadius(), legacy.getMaxRadius());
        assertEquals(baseline.getMaxFiles(), legacy.getMaxFiles());
        assertEquals(baseline.getMaxSnippetLines(), legacy.getMaxSnippetLines());
        assertFalse(legacy.isReadOnly());
        assertNull(legacy.getServiceName());
        assertEquals(baseline.getGraph().getPath(), legacy.getGraph().getPath());
    }

    @Test
    void nullNeo4jSectionDoesNotNpe() {
        // Hand-roll a ServingConfig where neo4j is explicitly null.
        CodeIqUnifiedConfig u = new CodeIqUnifiedConfig(
                ProjectConfig.empty(),
                IndexingConfig.empty(),
                new ServingConfig(null, null, null, null),
                new McpConfig(null, null, null,
                        McpAuthConfig.empty(),
                        McpLimitsConfig.empty(),
                        McpToolsConfig.empty()),
                ObservabilityConfig.empty(),
                DetectorsConfig.empty()
        );

        CodeIqConfig legacy = assertDoesNotThrow(() -> UnifiedConfigAdapter.toCodeIqConfig(u));
        assertEquals(new CodeIqConfig().getGraph().getPath(), legacy.getGraph().getPath());
    }

    @Test
    void newFieldsProjectCorrectly() {
        CodeIqUnifiedConfig u = new CodeIqUnifiedConfig(
                new ProjectConfig(null, null, "billing", List.of()),
                new IndexingConfig(
                        List.of(), List.of(), List.of(),
                        null, null, null, null,
                        25,   // maxDepth
                        17,   // maxRadius
                        500,  // maxFiles
                        12    // maxSnippetLines
                ),
                ServingConfig.empty(),
                new McpConfig(null, null, null,
                        McpAuthConfig.empty(),
                        McpLimitsConfig.empty(),
                        McpToolsConfig.empty()),
                ObservabilityConfig.empty(),
                DetectorsConfig.empty()
        );

        CodeIqConfig legacy = UnifiedConfigAdapter.toCodeIqConfig(u);

        assertEquals(25, legacy.getMaxDepth());
        assertEquals(17, legacy.getMaxRadius());
        assertEquals(500, legacy.getMaxFiles());
        assertEquals(12, legacy.getMaxSnippetLines());
        assertEquals("billing", legacy.getServiceName());
    }
}
