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

    // ---- Legacy file-read robustness on the canonical loadFrom() path ---------

    @Test
    void emptyLegacyFileReturnsEmptyOverlay(@TempDir Path tempDir) throws IOException {
        // Empty .osscodeiq.yml parses to null; loadFrom must treat it as "no
        // overlay" rather than crashing. The deprecation WARN still fires
        // because a (zero-byte) legacy file was present on disk.
        Files.writeString(tempDir.resolve(".osscodeiq.yml"), "", StandardCharsets.UTF_8);

        ProjectConfigLoader.LoadResult r = new ProjectConfigLoader().loadFrom(tempDir);
        assertEquals(CodeIqUnifiedConfig.empty(), r.config());
        assertTrue(r.deprecationWarningEmitted(),
                ".osscodeiq.yml presence (even empty) must emit a deprecation warning");
    }

    @Test
    void invalidYamlInLegacyFileDoesNotCrash(@TempDir Path tempDir) throws IOException {
        // Malformed YAML in the legacy file must not bubble up as an unchecked
        // exception. The legacy-translation path logs a WARN and returns empty.
        Files.writeString(tempDir.resolve(".osscodeiq.yml"),
                "{{invalid yaml content", StandardCharsets.UTF_8);

        ProjectConfigLoader.LoadResult r = new ProjectConfigLoader().loadFrom(tempDir);
        assertEquals(CodeIqUnifiedConfig.empty(), r.config(),
                "malformed legacy YAML must produce an empty overlay, not a crash");
        assertTrue(r.deprecationWarningEmitted());
    }
}
