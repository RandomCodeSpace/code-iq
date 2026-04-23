package io.github.randomcodespace.iq.config.unified;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads a single codeiq.yml file into a CodeIqUnifiedConfig overlay.
 * Missing file => CodeIqUnifiedConfig.empty(). Malformed YAML or type
 * mismatches throw ConfigLoadException with the file path and failing
 * field name in the message.
 *
 * <p>Key casing policy: snake_case is the primary, canonical form for every
 * leaf key. camelCase spellings are accepted as deprecated aliases for one
 * release so users with in-flight configs keep working. When both spellings
 * appear in the same file for the same leaf, the snake_case value wins and
 * a single WARN is logged naming the camelCase form as deprecated. Each
 * deprecated alias produces at most one WARN per load() call (per-file
 * dedupe) so a large legacy file does not spam the log.
 */
public final class UnifiedConfigLoader {
    private static final Logger log = LoggerFactory.getLogger(UnifiedConfigLoader.class);

    private UnifiedConfigLoader() {}

    public static CodeIqUnifiedConfig load(Path path) {
        if (path == null || !Files.exists(path)) {
            return CodeIqUnifiedConfig.empty();
        }
        String yaml;
        try {
            yaml = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigLoadException("Cannot read config file " + path, e);
        }
        Yaml parser = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object raw;
        try {
            raw = parser.load(yaml);
        } catch (YAMLException e) {
            throw new ConfigLoadException(
                    "Malformed YAML in " + path + ": " + e.getMessage(), e);
        }
        if (raw == null) return CodeIqUnifiedConfig.empty();
        if (!(raw instanceof Map<?, ?> m)) {
            throw new ConfigLoadException(
                    "Top-level of " + path + " must be a mapping, got: " + raw.getClass().getSimpleName());
        }
        try {
            return fromMap(m, path);
        } catch (ClassCastException | IllegalArgumentException e) {
            throw new ConfigLoadException(
                    "Type mismatch in " + path + ": " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static CodeIqUnifiedConfig fromMap(Map<?, ?> m, Path path) {
        // Per-load dedupe: every deprecated-alias form warned only once per file.
        Set<String> warnedAliases = new HashSet<>();
        return new CodeIqUnifiedConfig(
                projectFrom((Map<String, Object>) m.get("project"), path, warnedAliases),
                indexingFrom((Map<String, Object>) m.get("indexing"), path, warnedAliases),
                servingFrom((Map<String, Object>) m.get("serving"), path, warnedAliases),
                mcpFrom((Map<String, Object>) m.get("mcp"), path, warnedAliases),
                observabilityFrom((Map<String, Object>) m.get("observability"), path, warnedAliases),
                detectorsFrom((Map<String, Object>) m.get("detectors"))
        );
    }

    @SuppressWarnings("unchecked")
    private static ProjectConfig projectFrom(Map<String, Object> m, Path path, Set<String> warned) {
        if (m == null) return ProjectConfig.empty();
        List<Map<String, Object>> modRaw = (List<Map<String, Object>>) m.get("modules");
        List<ModuleConfig> mods = modRaw == null ? List.of()
                : modRaw.stream().map(x -> new ModuleConfig(
                        (String) x.get("path"),
                        (String) x.get("type"),
                        (String) x.get("name"),
                        (String) x.get("kind"))).toList();
        // project.service_name (canonical) / serviceName (deprecated alias)
        String serviceName = (String) pick(m, "project", "service_name", "serviceName", path, warned);
        return new ProjectConfig(
                (String) m.get("name"),
                (String) m.getOrDefault("root", "."),
                serviceName,
                mods);
    }

    private static IndexingConfig indexingFrom(Map<String, Object> m, Path path, Set<String> warned) {
        if (m == null) return IndexingConfig.empty();
        return new IndexingConfig(
                asStringList(m.get("languages")),
                asStringList(m.get("include")),
                asStringList(m.get("exclude")),
                (Boolean) m.get("incremental"),
                (String) pick(m, "indexing", "cache_dir", "cacheDir", path, warned),
                m.get("parallelism") == null ? null : String.valueOf(m.get("parallelism")),
                requireIntOrNull(pick(m, "indexing", "batch_size", "batchSize", path, warned),
                        path, "indexing.batch_size"),
                requireIntOrNull(m.get("max_depth"), path, "indexing.max_depth"),
                requireIntOrNull(m.get("max_radius"), path, "indexing.max_radius"),
                requireIntOrNull(m.get("max_files"), path, "indexing.max_files"),
                requireIntOrNull(m.get("max_snippet_lines"), path, "indexing.max_snippet_lines"));
    }

    @SuppressWarnings("unchecked")
    private static ServingConfig servingFrom(Map<String, Object> m, Path path, Set<String> warned) {
        if (m == null) return ServingConfig.empty();
        Neo4jConfig n4j = neo4jFrom((Map<String, Object>) m.get("neo4j"), path, warned);
        return new ServingConfig(
                requireIntOrNull(m.get("port"), path, "serving.port"),
                (String) pick(m, "serving", "bind_address", "bindAddress", path, warned),
                (Boolean) pick(m, "serving", "read_only", "readOnly", path, warned),
                n4j);
    }

    private static Neo4jConfig neo4jFrom(Map<String, Object> m, Path path, Set<String> warned) {
        if (m == null) return Neo4jConfig.empty();
        return new Neo4jConfig(
                (String) m.get("dir"),
                requireIntOrNull(pick(m, "serving.neo4j", "page_cache_mb", "pageCacheMb", path, warned),
                        path, "serving.neo4j.page_cache_mb"),
                requireIntOrNull(pick(m, "serving.neo4j", "heap_initial_mb", "heapInitialMb", path, warned),
                        path, "serving.neo4j.heap_initial_mb"),
                requireIntOrNull(pick(m, "serving.neo4j", "heap_max_mb", "heapMaxMb", path, warned),
                        path, "serving.neo4j.heap_max_mb"));
    }

    @SuppressWarnings("unchecked")
    private static McpConfig mcpFrom(Map<String, Object> m, Path path, Set<String> warned) {
        if (m == null) return McpConfig.empty();
        Map<String, Object> auth = (Map<String, Object>) m.get("auth");
        Map<String, Object> lim  = (Map<String, Object>) m.get("limits");
        Map<String, Object> tls  = (Map<String, Object>) m.get("tools");
        return new McpConfig(
                (Boolean) m.get("enabled"),
                (String) m.get("transport"),
                (String) pick(m, "mcp", "base_path", "basePath", path, warned),
                auth == null ? McpAuthConfig.empty() : new McpAuthConfig(
                        (String) auth.get("mode"),
                        (String) pick(auth, "mcp.auth", "token_env", "tokenEnv", path, warned)),
                lim == null ? McpLimitsConfig.empty() : new McpLimitsConfig(
                        requireIntOrNull(pick(lim, "mcp.limits", "per_tool_timeout_ms", "perToolTimeoutMs", path, warned),
                                path, "mcp.limits.per_tool_timeout_ms"),
                        requireIntOrNull(pick(lim, "mcp.limits", "max_results", "maxResults", path, warned),
                                path, "mcp.limits.max_results"),
                        requireLongOrNull(pick(lim, "mcp.limits", "max_payload_bytes", "maxPayloadBytes", path, warned),
                                path, "mcp.limits.max_payload_bytes"),
                        requireIntOrNull(pick(lim, "mcp.limits", "rate_per_minute", "ratePerMinute", path, warned),
                                path, "mcp.limits.rate_per_minute")),
                tls == null ? McpToolsConfig.empty() : new McpToolsConfig(
                        asStringList(tls.get("enabled")),
                        asStringList(tls.get("disabled"))));
    }

    private static ObservabilityConfig observabilityFrom(Map<String, Object> m, Path path, Set<String> warned) {
        if (m == null) return ObservabilityConfig.empty();
        return new ObservabilityConfig(
                (Boolean) m.get("metrics"),
                (Boolean) m.get("tracing"),
                (String) pick(m, "observability", "log_format", "logFormat", path, warned),
                (String) pick(m, "observability", "log_level", "logLevel", path, warned));
    }

    @SuppressWarnings("unchecked")
    private static DetectorsConfig detectorsFrom(Map<String, Object> m) {
        if (m == null) return DetectorsConfig.empty();
        Map<String, DetectorOverride> overrides = new LinkedHashMap<>();
        Map<String, Object> raw = (Map<String, Object>) m.getOrDefault("overrides", Map.of());
        for (var e : raw.entrySet()) {
            Map<String, Object> v = (Map<String, Object>) e.getValue();
            overrides.put(e.getKey(), new DetectorOverride(v == null ? null : (Boolean) v.get("enabled")));
        }
        return new DetectorsConfig(asStringList(m.get("profiles")), overrides);
    }

    /**
     * Returns the value for a leaf that has both a canonical snake_case key and a
     * deprecated camelCase alias. Precedence:
     * <ol>
     *   <li>If the canonical key is present, use it. If the alias is <em>also</em>
     *       present (conflict), emit a WARN and discard the alias.</li>
     *   <li>Otherwise, if only the alias is present, use it and emit a WARN.</li>
     *   <li>Otherwise, return {@code null} (unset).</li>
     * </ol>
     * The {@code warned} set guarantees one WARN per alias per file.
     */
    private static Object pick(Map<String, Object> m, String section,
                               String canonical, String alias,
                               Path path, Set<String> warned) {
        boolean hasCanonical = m.containsKey(canonical);
        boolean hasAlias = m.containsKey(alias);
        String aliasPath = section + "." + alias;
        String canonicalPath = section + "." + canonical;
        if (hasCanonical && hasAlias) {
            if (warned.add(aliasPath)) {
                log.warn("codeiq.yml {}: both '{}' and deprecated alias '{}' set; using "
                        + "'{}'. Remove '{}' -- camelCase keys will be removed in a "
                        + "future release.", path, canonicalPath, aliasPath, canonicalPath, aliasPath);
            }
            return m.get(canonical);
        }
        if (hasAlias) {
            if (warned.add(aliasPath)) {
                log.warn("codeiq.yml {}: deprecated camelCase key '{}' -- rename to "
                        + "'{}'. camelCase keys will be removed in a future release.",
                        path, aliasPath, canonicalPath);
            }
            return m.get(alias);
        }
        return m.get(canonical);
    }

    private static List<String> asStringList(Object o) {
        if (o == null) return List.of();
        if (o instanceof List<?> l) return l.stream().map(String::valueOf).toList();
        throw new IllegalArgumentException("expected list, got: " + o.getClass().getSimpleName());
    }

    private static Integer requireIntOrNull(Object o, Path path, String field) {
        if (o == null) return null;
        if (o instanceof Number n) return n.intValue();
        throw new IllegalArgumentException(field + " must be an integer; got " + o);
    }

    private static Long requireLongOrNull(Object o, Path path, String field) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        throw new IllegalArgumentException(field + " must be an integer; got " + o);
    }
}
