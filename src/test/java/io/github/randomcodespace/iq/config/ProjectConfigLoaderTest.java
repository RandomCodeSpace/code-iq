package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProjectConfigLoaderTest {

    // ---- New LoadResult-based API (Task 12: .osscodeiq.yml deprecation shim) ----

    @Test
    void preferCodeiqYmlWhenBothPresent(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve("codeiq.yml"), "serving:\n  port: 9000\n");
        Files.writeString(repo.resolve(".osscodeiq.yml"), "serving:\n  port: 9999\n");
        ProjectConfigLoader.LoadResult r = new ProjectConfigLoader().loadFrom(repo);
        assertEquals(9000, r.config().serving().port());
        assertFalse(r.deprecationWarningEmitted());
    }

    @Test
    void fallsBackToOsscodeIqWithWarn(@TempDir Path repo) throws Exception {
        Files.writeString(repo.resolve(".osscodeiq.yml"), "serving:\n  port: 8888\n");
        ProjectConfigLoader.LoadResult r = new ProjectConfigLoader().loadFrom(repo);
        assertEquals(8888, r.config().serving().port());
        assertTrue(r.deprecationWarningEmitted(),
                "must emit a migration warning when falling back to .osscodeiq.yml");
    }

    @Test
    void neitherFilePresentReturnsEmptyConfig(@TempDir Path repo) {
        ProjectConfigLoader.LoadResult r = new ProjectConfigLoader().loadFrom(repo);
        assertEquals(CodeIqUnifiedConfig.empty(), r.config());
        assertFalse(r.deprecationWarningEmitted());
    }

    @Test
    void fallbackOsscodeiqWithFlatKeysTranslatesToUnifiedOverlay(@TempDir Path repo) throws Exception {
        String yaml = """
                max_depth: 25
                max_radius: 8
                cache_dir: .custom-cache
                root_path: /repo
                """;
        Files.writeString(repo.resolve(".osscodeiq.yml"), yaml);

        ProjectConfigLoader.LoadResult r = new ProjectConfigLoader().loadFrom(repo);

        assertEquals(25, r.config().indexing().maxDepth());
        assertEquals(8, r.config().indexing().maxRadius());
        assertEquals(".custom-cache", r.config().indexing().cacheDir());
        assertEquals("/repo", r.config().project().root());
        assertTrue(r.deprecationWarningEmitted(),
                "must emit a migration warning when falling back to .osscodeiq.yml");
    }

    @Test
    void fallbackOsscodeiqWithNewShapeStillWorks(@TempDir Path repo) throws Exception {
        // A .osscodeiq.yml that has already been rewritten in the new nested schema
        // (e.g., a user renamed codeiq.yml back, or copy-pasted the new sample) must
        // continue to work — delegate to UnifiedConfigLoader, still warn.
        Files.writeString(repo.resolve(".osscodeiq.yml"), "serving:\n  port: 9999\n");

        ProjectConfigLoader.LoadResult r = new ProjectConfigLoader().loadFrom(repo);

        assertEquals(9999, r.config().serving().port());
        assertTrue(r.deprecationWarningEmitted());
    }

    @Test
    void mixedLegacyFlatAndNestedKeysPrefersLegacyPath(@TempDir Path repo) throws Exception {
        // Documented behavior (see javadoc on ProjectConfigLoader#readAndTranslateLegacy):
        // presence of ANY legacy flat key at the root triggers the legacy translator,
        // so flat values are honored. Nested sections that lack a flat equivalent
        // (serving / mcp / observability / detectors) are intentionally NOT read in the
        // legacy-mixed case — a pure new-shape file should drop the flat keys first.
        String yaml = """
                max_depth: 25
                indexing:
                  batch_size: 100
                """;
        Files.writeString(repo.resolve(".osscodeiq.yml"), yaml);

        ProjectConfigLoader.LoadResult r = new ProjectConfigLoader().loadFrom(repo);

        assertEquals(25, r.config().indexing().maxDepth(),
                "flat max_depth must translate even when a nested indexing block is present");
        // In legacy-mixed mode, pipeline.batch-size (legacy schema) is the batch-size
        // source; a bare `indexing.batch_size` nested block is intentionally ignored.
        assertNull(r.config().indexing().batchSize(),
                "nested indexing.batch_size is not honored in legacy-mixed mode (documented)");
        assertTrue(r.deprecationWarningEmitted());
    }

    // ---- Legacy static API retained for back-compat call sites (Analyzer, CliOutput) ----

    @Test
    void loadFromYmlFile(@TempDir Path tempDir) throws IOException {
        String yamlContent = """
                cache_dir: .my-cache
                max_depth: 5
                max_radius: 3
                """;
        Files.writeString(tempDir.resolve(".code-iq.yml"), yamlContent, StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        boolean loaded = ProjectConfigLoader.loadIfPresent(tempDir, config);

        assertTrue(loaded, "Should find and load .code-iq.yml");
        assertEquals(".my-cache", config.getCacheDir());
        assertEquals(5, config.getMaxDepth());
        assertEquals(3, config.getMaxRadius());
    }

    @Test
    void loadFromYamlFile(@TempDir Path tempDir) throws IOException {
        String yamlContent = """
                cache_dir: custom-cache
                max_depth: 7
                """;
        Files.writeString(tempDir.resolve(".code-iq.yaml"), yamlContent, StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        boolean loaded = ProjectConfigLoader.loadIfPresent(tempDir, config);

        assertTrue(loaded, "Should find and load .code-iq.yaml");
        assertEquals("custom-cache", config.getCacheDir());
        assertEquals(7, config.getMaxDepth());
    }

    @Test
    void ymlTakesPrecedenceOverYaml(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(".code-iq.yml"),
                "cache_dir: from-yml\n", StandardCharsets.UTF_8);
        Files.writeString(tempDir.resolve(".code-iq.yaml"),
                "cache_dir: from-yaml\n", StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        ProjectConfigLoader.loadIfPresent(tempDir, config);

        assertEquals("from-yml", config.getCacheDir(), ".yml should take precedence");
    }

    @Test
    void returnsFalseWhenNoConfigFile(@TempDir Path tempDir) {
        var config = new CodeIqConfig();
        boolean loaded = ProjectConfigLoader.loadIfPresent(tempDir, config);

        assertFalse(loaded, "Should return false when no config file exists");
        // Config should retain defaults
        assertEquals(".code-iq/cache", config.getCacheDir());
        assertEquals(10, config.getMaxDepth());
    }

    @Test
    void handlesEmptyConfigFile(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(".osscodeiq.yml"), "", StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        boolean loaded = ProjectConfigLoader.loadIfPresent(tempDir, config);

        // Empty YAML parses to null, so no overrides applied
        assertFalse(loaded, "Should not apply overrides from empty config");
    }

    @Test
    void handlesInvalidYaml(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(".osscodeiq.yml"),
                "{{invalid yaml content", StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        boolean loaded = ProjectConfigLoader.loadIfPresent(tempDir, config);

        assertFalse(loaded, "Should not crash on invalid YAML");
        assertEquals(".code-iq/cache", config.getCacheDir());
    }

    @Test
    void partialOverridesPreserveDefaults(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(".osscodeiq.yml"),
                "max_depth: 3\n", StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        boolean loaded = ProjectConfigLoader.loadIfPresent(tempDir, config);

        assertTrue(loaded);
        assertEquals(3, config.getMaxDepth());
        // Other values should remain at defaults
        assertEquals(".code-iq/cache", config.getCacheDir());
        assertEquals(10, config.getMaxRadius());
    }
}
