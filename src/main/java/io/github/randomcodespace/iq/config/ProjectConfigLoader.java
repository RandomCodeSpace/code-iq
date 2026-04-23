package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import io.github.randomcodespace.iq.config.unified.UnifiedConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the project-scoped {@code codeiq.yml} (preferred) or, if absent, the
 * legacy {@code .osscodeiq.yml} with a one-time deprecation warning. The
 * legacy fallback branch will be removed one release after the warning first
 * shipped.
 *
 * <p>This class exposes two surfaces:
 * <ul>
 *   <li>The new {@link #loadFrom(Path)} instance method returning a
 *       {@link LoadResult} with a {@link CodeIqUnifiedConfig} overlay for
 *       the PROJECT layer. This is the Phase B path consumed by
 *       {@link UnifiedConfigBeans}.
 *   <li>The legacy {@link #loadIfPresent(Path, CodeIqConfig)} and
 *       {@link #loadProjectConfig(Path)} static methods kept for the
 *       existing {@code Analyzer} / {@code CliOutput} call sites. Those
 *       still mutate the legacy {@link CodeIqConfig} / {@link ProjectConfig}
 *       records directly and will be retired when their callers migrate to
 *       {@link CodeIqUnifiedConfig} (Task 13+).
 * </ul>
 */
@Component
public class ProjectConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ProjectConfigLoader.class);
    private static final String NEW_NAME = "codeiq.yml";
    private static final String OLD_NAME = ".osscodeiq.yml";
    private static final String[] LEGACY_CONFIG_FILE_NAMES = {
            ".code-iq.yml", ".code-iq.yaml", ".osscodeiq.yml", ".osscodeiq.yaml"
    };

    /** Deprecation warning is emitted at most once per JVM, regardless of how many callers load. */
    private static final AtomicBoolean DEPRECATION_WARNED = new AtomicBoolean(false);

    public ProjectConfigLoader() {
        // default bean constructor
    }

    /**
     * Result of loading the project-scoped config.
     *
     * @param config                     the loaded overlay in unified-config form, or
     *                                   {@link CodeIqUnifiedConfig#empty()} if neither file exists
     * @param deprecationWarningEmitted  {@code true} iff the loader fell back to
     *                                   {@code .osscodeiq.yml} for this call
     */
    public record LoadResult(CodeIqUnifiedConfig config, boolean deprecationWarningEmitted) {}

    /**
     * Loads the project-scoped config overlay from {@code repoRoot}. Prefers
     * {@code codeiq.yml}; if absent, falls back to the legacy
     * {@code .osscodeiq.yml} and emits a one-time SLF4J {@code WARN} pointing
     * to the new filename. If neither is present, returns an empty overlay.
     *
     * <p>Emits the deprecation warning at most once per JVM (subsequent calls
     * still set {@code deprecationWarningEmitted=true} on the returned
     * {@link LoadResult} so callers can label provenance appropriately).
     */
    public LoadResult loadFrom(Path repoRoot) {
        Path newFile = repoRoot.resolve(NEW_NAME);
        if (Files.exists(newFile)) {
            return new LoadResult(UnifiedConfigLoader.load(newFile), false);
        }
        Path oldFile = repoRoot.resolve(OLD_NAME);
        if (Files.exists(oldFile)) {
            if (DEPRECATION_WARNED.compareAndSet(false, true)) {
                log.warn("DEPRECATED: {} is loaded but will be removed in a future release. "
                                + "Rename to {} (same YAML content) at your repo root.",
                        oldFile, NEW_NAME);
            }
            return new LoadResult(UnifiedConfigLoader.load(oldFile), true);
        }
        return new LoadResult(CodeIqUnifiedConfig.empty(), false);
    }

    // ---------------------------------------------------------------
    // Legacy static API — retained for pre-unified call sites only.
    // Remove when Analyzer/CliOutput migrate to CodeIqUnifiedConfig.
    // ---------------------------------------------------------------

    /**
     * Look for {@code .code-iq.yml}/{@code .yaml} or {@code .osscodeiq.yml}/{@code .yaml}
     * in the given directory. If found, parse it and apply matching properties to the
     * legacy {@link CodeIqConfig}.
     *
     * @deprecated Legacy path; new code should go through
     *             {@link #loadFrom(Path)} and the unified config tree.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public static boolean loadIfPresent(Path directory, CodeIqConfig config) {
        for (String name : LEGACY_CONFIG_FILE_NAMES) {
            Path configFile = directory.resolve(name);
            if (Files.isRegularFile(configFile)) {
                try {
                    String content = Files.readString(configFile, StandardCharsets.UTF_8);
                    Yaml yaml = new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(
                            new org.yaml.snakeyaml.LoaderOptions()));
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
     * @deprecated Legacy path; new code should go through
     *             {@link #loadFrom(Path)} and the unified config tree.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public static ProjectConfig loadProjectConfig(Path directory) {
        for (String name : LEGACY_CONFIG_FILE_NAMES) {
            Path configFile = directory.resolve(name);
            if (Files.isRegularFile(configFile)) {
                try {
                    String content = Files.readString(configFile, StandardCharsets.UTF_8);
                    Yaml yaml = new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(
                            new org.yaml.snakeyaml.LoaderOptions()));
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
     * Parse a YAML data map into a structured {@link ProjectConfig}.
     *
     * @deprecated Legacy path; new code should go through
     *             {@link #loadFrom(Path)} and the unified config tree.
     */
    @Deprecated
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
