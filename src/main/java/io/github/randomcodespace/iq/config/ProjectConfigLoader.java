package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import io.github.randomcodespace.iq.config.unified.DetectorsConfig;
import io.github.randomcodespace.iq.config.unified.IndexingConfig;
import io.github.randomcodespace.iq.config.unified.McpConfig;
import io.github.randomcodespace.iq.config.unified.ObservabilityConfig;
import io.github.randomcodespace.iq.config.unified.ServingConfig;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the project-scoped {@code codeiq.yml} (preferred) or, if absent, the
 * legacy {@code .osscodeiq.yml} with a one-time-per-path deprecation warning.
 * The legacy fallback branch will be removed one release after the warning
 * first shipped.
 *
 * <p>Surface: the {@link #loadFrom(Path)} instance method returns a
 * {@link LoadResult} with a {@link CodeIqUnifiedConfig} overlay for the
 * PROJECT layer. This is the only public loader surface; it is consumed by
 * {@code UnifiedConfigBeans} at startup.
 */
@Component
public class ProjectConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ProjectConfigLoader.class);
    private static final String NEW_NAME = "codeiq.yml";
    private static final String OLD_NAME = ".osscodeiq.yml";

    /**
     * Top-level flat keys recognised by the pre-Phase-B {@code .osscodeiq.yml}
     * schema. Presence of any of these at the YAML root triggers the
     * legacy-to-unified translator.
     */
    private static final Set<String> LEGACY_FLAT_KEYS = Set.of(
            "root_path", "service_name", "cache_dir",
            "max_depth", "max_radius", "max_files", "max_snippet_lines",
            "batch_size");

    /**
     * Per-canonical-path dedupe of the deprecation WARN so multi-workspace
     * callers each see one warning. Keyed by canonical (realPath or
     * normalized-absolute) string so symlinked/relative aliases collapse.
     */
    private static final Set<String> WARNED_PATHS = ConcurrentHashMap.newKeySet();

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
     * {@code .osscodeiq.yml} and emits a per-path SLF4J {@code WARN} pointing
     * to the new filename. If neither is present, returns an empty overlay.
     *
     * <p>The deprecation warning is logged at most once per canonical file path
     * per JVM. The returned {@link LoadResult#deprecationWarningEmitted()} is
     * still {@code true} on every fallback call so callers can label provenance
     * appropriately.
     */
    public LoadResult loadFrom(Path repoRoot) {
        Path newFile = repoRoot.resolve(NEW_NAME);
        if (Files.exists(newFile)) {
            return new LoadResult(UnifiedConfigLoader.load(newFile), false);
        }
        Path oldFile = repoRoot.resolve(OLD_NAME);
        if (Files.exists(oldFile)) {
            LegacyParse parsed = readAndTranslateLegacy(oldFile);
            String canonical = canonicalize(oldFile);
            if (WARNED_PATHS.add(canonical)) {
                log.warn(".osscodeiq.yml at {} is deprecated. Translated {} key(s) into the unified config; "
                                + "migrate to {} (see README for the new schema).",
                        oldFile, parsed.translatedKeyCount, NEW_NAME);
            }
            return new LoadResult(parsed.config, true);
        }
        return new LoadResult(CodeIqUnifiedConfig.empty(), false);
    }

    private static String canonicalize(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (IOException e) {
            return p.toAbsolutePath().normalize().toString();
        }
    }

    /** Container for the legacy-parse result + a count of flat keys translated (for the WARN message). */
    private record LegacyParse(CodeIqUnifiedConfig config, int translatedKeyCount) {}

    /**
     * Reads {@code oldFile}, detects whether it uses the legacy flat schema
     * (top-level {@code max_depth}, {@code cache_dir}, etc.), and produces a
     * {@link CodeIqUnifiedConfig} overlay.
     *
     * <p><b>Precedence when a file mixes shapes:</b> legacy flat keys take
     * priority over any nested {@code indexing}/{@code project} sections in
     * the same file. Rationale: a user who still has flat keys is clearly on
     * the pre-Phase-B schema; honoring the flat values prevents silent data
     * loss while the warning tells them to migrate. Nested keys under
     * {@code serving}/{@code mcp}/{@code observability}/{@code detectors}
     * (which have no legacy flat equivalent) are still read via the unified
     * loader path and composed into the overlay.
     */
    @SuppressWarnings("unchecked")
    private static LegacyParse readAndTranslateLegacy(Path oldFile) {
        Map<String, Object> raw;
        try {
            String content = Files.readString(oldFile, StandardCharsets.UTF_8);
            Yaml yaml = new Yaml(new org.yaml.snakeyaml.constructor.SafeConstructor(
                    new org.yaml.snakeyaml.LoaderOptions()));
            raw = yaml.load(content);
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", oldFile, e.getMessage());
            return new LegacyParse(CodeIqUnifiedConfig.empty(), 0);
        } catch (Exception e) {
            log.warn("Failed to parse {}: {}", oldFile, e.getMessage());
            return new LegacyParse(CodeIqUnifiedConfig.empty(), 0);
        }
        if (raw == null || raw.isEmpty()) {
            return new LegacyParse(CodeIqUnifiedConfig.empty(), 0);
        }

        boolean hasLegacy = false;
        for (String k : LEGACY_FLAT_KEYS) {
            if (raw.containsKey(k)) { hasLegacy = true; break; }
        }

        if (!hasLegacy) {
            // Pure new-shape content accidentally saved as .osscodeiq.yml.
            // Delegate to the canonical loader so nested sections work as-is.
            return new LegacyParse(UnifiedConfigLoader.load(oldFile), 0);
        }

        return new LegacyParse(translateLegacyToUnified(raw), countLegacyKeys(raw));
    }

    private static int countLegacyKeys(Map<String, Object> raw) {
        int n = 0;
        for (String k : LEGACY_FLAT_KEYS) {
            if (raw.containsKey(k)) n++;
        }
        return n;
    }

    /**
     * Translator: maps pre-Phase-B flat keys at the YAML root to a
     * {@link CodeIqUnifiedConfig} overlay. Pulls {@code languages},
     * {@code detectors.*}, {@code exclude}, {@code parsers}, and {@code pipeline.*}
     * from their nested positions and adds the flat-key mapping documented in
     * the Phase B migration table:
     *
     * <pre>
     *   root_path           -> project.root
     *   service_name        -> project.serviceName
     *   cache_dir           -> indexing.cacheDir
     *   max_depth           -> indexing.maxDepth
     *   max_radius          -> indexing.maxRadius
     *   max_files           -> indexing.maxFiles
     *   max_snippet_lines   -> indexing.maxSnippetLines
     *   batch_size          -> indexing.batchSize
     *   detector_categories -> detectors.categories (flat top-level alias)
     *   detector_include    -> detectors.include    (flat top-level alias)
     * </pre>
     *
     * <p>{@code parsers} in the legacy file is a map ({@code {lang: parserName}});
     * the unified tree carries {@code indexing.parsers} as {@code List<String>},
     * so the map's values are flattened into the list (Analyzer never consumed
     * the per-language map at runtime — a list is sufficient).
     *
     * <p>Only section leaves present in {@code raw} are set; absent fields stay
     * {@code null} so {@link io.github.randomcodespace.iq.config.unified.ConfigMerger}
     * correctly falls through to lower layers.
     */
    @SuppressWarnings("unchecked")
    static CodeIqUnifiedConfig translateLegacyToUnified(Map<String, Object> raw) {
        // --- project layer ---
        String root = raw.containsKey("root_path") ? String.valueOf(raw.get("root_path")) : null;
        String serviceName = raw.containsKey("service_name") ? String.valueOf(raw.get("service_name")) : null;
        io.github.randomcodespace.iq.config.unified.ProjectConfig projectU =
                new io.github.randomcodespace.iq.config.unified.ProjectConfig(null, root, serviceName, List.of());

        // --- indexing layer ---
        List<String> languages = toStringList(raw.get("languages"));
        List<String> exclude = toStringList(raw.get("exclude"));

        String cacheDir = raw.containsKey("cache_dir") ? String.valueOf(raw.get("cache_dir")) : null;
        Integer maxDepth = raw.containsKey("max_depth") ? toInteger(raw.get("max_depth")) : null;
        Integer maxRadius = raw.containsKey("max_radius") ? toInteger(raw.get("max_radius")) : null;
        Integer maxFiles = raw.containsKey("max_files") ? toInteger(raw.get("max_files")) : null;
        Integer maxSnippetLines = raw.containsKey("max_snippet_lines")
                ? toInteger(raw.get("max_snippet_lines")) : null;

        // pipeline: nested pipeline.batch-size / pipeline.parallelism wins over any flat batch_size.
        Integer parallelism = null;
        Integer nestedBatchSize = null;
        if (raw.get("pipeline") instanceof Map<?, ?> pipeline) {
            parallelism = toInteger(pipeline.get("parallelism"));
            nestedBatchSize = toInteger(pipeline.get("batch-size"));
        }
        Integer batchSize = nestedBatchSize;
        if (batchSize == null && raw.containsKey("batch_size")) {
            batchSize = toInteger(raw.get("batch_size"));
        }

        // parsers: legacy map {lang: parserName} flattened to List<String> of parser names.
        List<String> parsers = List.of();
        if (raw.get("parsers") instanceof Map<?, ?> parsersMap) {
            List<String> names = new ArrayList<>(parsersMap.size());
            for (Object v : parsersMap.values()) {
                if (v != null) names.add(String.valueOf(v));
            }
            parsers = List.copyOf(names);
        }

        IndexingConfig indexingU = new IndexingConfig(
                languages == null ? List.of() : languages,
                List.of(),
                exclude == null ? List.of() : exclude,
                null,           // incremental — no legacy flat equivalent
                cacheDir,
                parallelism,
                batchSize,
                maxDepth,
                maxRadius,
                maxFiles,
                maxSnippetLines,
                parsers);

        // --- detectors layer ---
        // Nested `detectors: { categories, include }` shape plus flat top-level
        // aliases `detector_categories` / `detector_include` so legacy
        // `.osscodeiq.yml` files that put the filters at the root continue to work.
        List<String> detectorCategories = null;
        List<String> detectorInclude = null;
        if (raw.get("detectors") instanceof Map<?, ?> detectors) {
            detectorCategories = toStringList(detectors.get("categories"));
            detectorInclude = toStringList(detectors.get("include"));
        }
        if (detectorCategories == null && raw.get("detector_categories") instanceof List<?> lc) {
            detectorCategories = lc.stream().map(String::valueOf).toList();
        }
        if (detectorInclude == null && raw.get("detector_include") instanceof List<?> li) {
            detectorInclude = li.stream().map(String::valueOf).toList();
        }
        DetectorsConfig detectorsU = new DetectorsConfig(
                List.of(),
                detectorCategories == null ? List.of() : detectorCategories,
                detectorInclude == null ? List.of() : detectorInclude,
                Map.of());

        return new CodeIqUnifiedConfig(
                projectU,
                indexingU,
                ServingConfig.empty(),
                McpConfig.empty(),
                ObservabilityConfig.empty(),
                detectorsU);
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
