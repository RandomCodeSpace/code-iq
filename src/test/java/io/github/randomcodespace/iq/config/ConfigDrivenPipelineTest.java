package io.github.randomcodespace.iq.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the config-driven pipeline filtering features
 * in ProjectConfigLoader and ProjectConfig.
 */
class ConfigDrivenPipelineTest {

    @Test
    void parseLanguagesFilter() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("languages", List.of("java", "python"));

        ProjectConfig config = ProjectConfigLoader.parseProjectConfig(data);

        assertTrue(config.hasLanguageFilter());
        assertEquals(List.of("java", "python"), config.getLanguages());
    }

    @Test
    void parseDetectorCategories() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("detectors", Map.of("categories", List.of("endpoints", "entities")));

        ProjectConfig config = ProjectConfigLoader.parseProjectConfig(data);

        assertTrue(config.hasDetectorCategoryFilter());
        assertEquals(List.of("endpoints", "entities"), config.getDetectorCategories());
    }

    @Test
    void parseDetectorInclude() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("detectors", Map.of("include", List.of("spring-rest-detector")));

        ProjectConfig config = ProjectConfigLoader.parseProjectConfig(data);

        assertTrue(config.hasDetectorIncludeFilter());
        assertEquals(List.of("spring-rest-detector"), config.getDetectorInclude());
    }

    @Test
    void parseExcludePatterns() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("exclude", List.of("**/generated/**", "**/test/**"));

        ProjectConfig config = ProjectConfigLoader.parseProjectConfig(data);

        assertTrue(config.hasExcludePatterns());
        assertEquals(List.of("**/generated/**", "**/test/**"), config.getExclude());
    }

    @Test
    void parseParsersMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("parsers", Map.of("java", "javaparser", "python", "regex"));

        ProjectConfig config = ProjectConfigLoader.parseProjectConfig(data);

        assertNotNull(config.getParsers());
        assertEquals("javaparser", config.getParsers().get("java"));
        assertEquals("regex", config.getParsers().get("python"));
    }

    @Test
    void parsePipelineSettings() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pipeline", Map.of("parallelism", 4, "batch-size", 100));

        ProjectConfig config = ProjectConfigLoader.parseProjectConfig(data);

        assertEquals(4, config.getPipelineParallelism());
        assertEquals(100, config.getPipelineBatchSize());
    }

    @Test
    void emptyConfigHasNoFilters() {
        ProjectConfig config = ProjectConfig.empty();

        assertFalse(config.hasLanguageFilter());
        assertFalse(config.hasDetectorCategoryFilter());
        assertFalse(config.hasDetectorIncludeFilter());
        assertFalse(config.hasExcludePatterns());
        assertNull(config.getLanguages());
        assertNull(config.getDetectorCategories());
        assertNull(config.getDetectorInclude());
        assertNull(config.getExclude());
        assertNull(config.getParsers());
        assertNull(config.getPipelineParallelism());
        assertNull(config.getPipelineBatchSize());
    }

    @Test
    void parseEmptyDataReturnsEmptyConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        ProjectConfig config = ProjectConfigLoader.parseProjectConfig(data);

        assertFalse(config.hasLanguageFilter());
        assertFalse(config.hasDetectorCategoryFilter());
    }

    @Test
    void loadProjectConfigFromFile(@TempDir Path tempDir) throws IOException {
        String yamlContent = """
                languages:
                  - java
                  - kotlin
                detectors:
                  categories:
                    - java
                    - config
                  include:
                    - spring-rest-detector
                pipeline:
                  parallelism: 8
                  batch-size: 50
                exclude:
                  - "**/generated/**"
                """;
        Files.writeString(tempDir.resolve(".code-iq.yml"), yamlContent, StandardCharsets.UTF_8);

        ProjectConfig config = ProjectConfigLoader.loadProjectConfig(tempDir);

        assertTrue(config.hasLanguageFilter());
        assertEquals(List.of("java", "kotlin"), config.getLanguages());
        assertTrue(config.hasDetectorCategoryFilter());
        assertEquals(List.of("java", "config"), config.getDetectorCategories());
        assertTrue(config.hasDetectorIncludeFilter());
        assertEquals(List.of("spring-rest-detector"), config.getDetectorInclude());
        assertEquals(8, config.getPipelineParallelism());
        assertEquals(50, config.getPipelineBatchSize());
        assertTrue(config.hasExcludePatterns());
    }

    @Test
    void loadProjectConfigReturnsEmptyWhenNoFile(@TempDir Path tempDir) {
        ProjectConfig config = ProjectConfigLoader.loadProjectConfig(tempDir);
        assertFalse(config.hasLanguageFilter());
        assertFalse(config.hasDetectorCategoryFilter());
    }

    @Test
    void parseFullConfig() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("languages", List.of("java"));
        data.put("detectors", Map.of(
                "categories", List.of("endpoints"),
                "include", List.of("spring-rest-detector")));
        data.put("parsers", Map.of("java", "javaparser"));
        data.put("pipeline", Map.of("parallelism", 2, "batch-size", 200));
        data.put("exclude", List.of("*.min.js"));

        ProjectConfig config = ProjectConfigLoader.parseProjectConfig(data);

        assertEquals(List.of("java"), config.getLanguages());
        assertEquals(List.of("endpoints"), config.getDetectorCategories());
        assertEquals(List.of("spring-rest-detector"), config.getDetectorInclude());
        assertEquals("javaparser", config.getParsers().get("java"));
        assertEquals(2, config.getPipelineParallelism());
        assertEquals(200, config.getPipelineBatchSize());
        assertEquals(List.of("*.min.js"), config.getExclude());
    }
}
