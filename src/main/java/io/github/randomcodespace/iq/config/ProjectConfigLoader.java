package io.github.randomcodespace.iq.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads project-level configuration from .osscodeiq.yml or .osscodeiq.yaml
 * found in the target directory, and applies overrides to {@link CodeIqConfig}.
 * <p>
 * Also produces a {@link ProjectConfig} with pipeline filter settings
 * (languages, detector categories, detector includes, exclude patterns, etc.).
 */
public final class ProjectConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ProjectConfigLoader.class);
    private static final String[] CONFIG_FILE_NAMES = {".code-iq.yml", ".code-iq.yaml", ".osscodeiq.yml", ".osscodeiq.yaml"};

    private ProjectConfigLoader() {
        // utility class
    }

    /**
     * Look for .osscodeiq.yml or .osscodeiq.yaml in the given directory.
     * If found, parse it and apply matching properties to the config.
     *
     * @param directory the project root directory to search
     * @param config    the config to apply overrides to
     * @return true if a config file was found and applied
     */
    @SuppressWarnings("unchecked")
    public static boolean loadIfPresent(Path directory, CodeIqConfig config) {
        for (String name : CONFIG_FILE_NAMES) {
            Path configFile = directory.resolve(name);
            if (Files.isRegularFile(configFile)) {
                try {
                    String content = Files.readString(configFile, StandardCharsets.UTF_8);
                    Yaml yaml = new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
                    Map<String, Object> data = yaml.load(content);
                    if (data != null) {
                        applyOverrides(data, config);
                        log.info("Loaded project config from {}", configFile);
                        return true;
                    }
                } catch (IOException e) {
                    log.warn("Failed to read config file {}: {}", configFile, e.getMessage());
                } catch (Exception e) {
                    log.warn("Failed to parse config file {}: {}", configFile, e.getMessage());
                }
            }
        }
        return false;
    }

    /**
     * Load the full project configuration including pipeline filter settings.
     *
     * @param directory the project root directory to search
     * @return parsed ProjectConfig, or {@link ProjectConfig#empty()} if no config found
     */
    @SuppressWarnings("unchecked")
    public static ProjectConfig loadProjectConfig(Path directory) {
        for (String name : CONFIG_FILE_NAMES) {
            Path configFile = directory.resolve(name);
            if (Files.isRegularFile(configFile)) {
                try {
                    String content = Files.readString(configFile, StandardCharsets.UTF_8);
                    Yaml yaml = new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(new org.yaml.snakeyaml.LoaderOptions()));
                    Map<String, Object> data = yaml.load(content);
                    if (data != null) {
                        log.info("Loaded project config from {}", configFile);
                        return parseProjectConfig(data);
                    }
                } catch (IOException e) {
                    log.warn("Failed to read config file {}: {}", configFile, e.getMessage());
                } catch (Exception e) {
                    log.warn("Failed to parse config file {}: {}", configFile, e.getMessage());
                }
            }
        }
        return ProjectConfig.empty();
    }

    /**
     * Parse a YAML data map into a structured ProjectConfig.
     * Supports:
     * <pre>
     * languages:
     *   - java
     *   - python
     * detectors:
     *   categories:
     *     - endpoints
     *     - entities
     *   include:
     *     - spring-rest-detector
     * parsers:
     *   java: javaparser
     * pipeline:
     *   parallelism: 4
     *   batch-size: 100
     * exclude:
     *   - "*.generated.java"
     * </pre>
     */
    @SuppressWarnings("unchecked")
    static ProjectConfig parseProjectConfig(Map<String, Object> data) {
        List<String> languages = toStringList(data.get("languages"));

        List<String> detectorCategories = null;
        List<String> detectorInclude = null;
        if (data.get("detectors") instanceof Map<?, ?> detectors) {
            detectorCategories = toStringList(detectors.get("categories"));
            detectorInclude = toStringList(detectors.get("include"));
        }

        List<String> exclude = toStringList(data.get("exclude"));

        Map<String, String> parsers = null;
        if (data.get("parsers") instanceof Map<?, ?> parsersMap) {
            parsers = new LinkedHashMap<>();
            for (var entry : parsersMap.entrySet()) {
                parsers.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }

        Integer parallelism = null;
        Integer batchSize = null;
        if (data.get("pipeline") instanceof Map<?, ?> pipeline) {
            parallelism = toInteger(pipeline.get("parallelism"));
            batchSize = toInteger(pipeline.get("batch-size"));
        }

        return new ProjectConfig(
                languages,
                detectorCategories,
                detectorInclude,
                exclude,
                parsers,
                parallelism,
                batchSize
        );
    }

    @SuppressWarnings("unchecked")
    private static void applyOverrides(Map<String, Object> data, CodeIqConfig config) {
        if (data.containsKey("cache_dir")) {
            config.setCacheDir(String.valueOf(data.get("cache_dir")));
        }
        if (data.containsKey("max_depth")) {
            config.setMaxDepth(toInt(data.get("max_depth"), config.getMaxDepth()));
        }
        if (data.containsKey("max_radius")) {
            config.setMaxRadius(toInt(data.get("max_radius"), config.getMaxRadius()));
        }
        // Nested analysis/output sections are recognized but not yet mapped to CodeIqConfig.
        // They are loaded and accessible via data map for CLI commands that need them.
    }

    private static int toInt(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static Integer toInteger(Object value) {
        if (value == null) return null;
        if (value instanceof Number n) {
            return n.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> toStringList(Object value) {
        if (value == null) return null;
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result.isEmpty() ? null : result;
        }
        return null;
    }
}
