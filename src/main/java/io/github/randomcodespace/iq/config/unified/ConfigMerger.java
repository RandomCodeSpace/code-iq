package io.github.randomcodespace.iq.config.unified;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Merges a list of CodeIqUnifiedConfig overlays in priority order (first entry
 * lowest priority, last entry highest). At each scalar leaf, a non-null value
 * in a higher-priority layer wins and replaces the value from lower layers.
 * Lists and maps follow whole-value replacement (NOT element-wise merge) —
 * this keeps behavior predictable and avoids surprising append semantics.
 *
 * The output also records the provenance (which layer set the final value
 * for each leaf path), used by the `config explain` command.
 */
public final class ConfigMerger {

    public record Input(ConfigLayer layer, String sourceLabel, CodeIqUnifiedConfig overlay) {}

    public MergedConfig merge(List<Input> layers) {
        CodeIqUnifiedConfig acc = CodeIqUnifiedConfig.empty();
        Map<String, ConfigProvenance> prov = new HashMap<>();
        for (Input layer : layers) {
            acc = mergeTwo(acc, layer, prov);
        }
        return new MergedConfig(acc, prov);
    }

    private CodeIqUnifiedConfig mergeTwo(CodeIqUnifiedConfig lo, Input hi,
                                         Map<String, ConfigProvenance> prov) {
        CodeIqUnifiedConfig hiCfg = hi.overlay();
        return new CodeIqUnifiedConfig(
                mergeProject(lo.project(), hiCfg.project(), hi, prov),
                mergeIndexing(lo.indexing(), hiCfg.indexing(), hi, prov),
                mergeServing(lo.serving(), hiCfg.serving(), hi, prov),
                mergeMcp(lo.mcp(), hiCfg.mcp(), hi, prov),
                mergeObservability(lo.observability(), hiCfg.observability(), hi, prov),
                mergeDetectors(lo.detectors(), hiCfg.detectors(), hi, prov)
        );
    }

    private ProjectConfig mergeProject(ProjectConfig lo, ProjectConfig hi, Input l, Map<String,ConfigProvenance> p) {
        return new ProjectConfig(
                take("project.name",         lo.name(),        hi.name(),        l, p),
                take("project.root",         lo.root(),        hi.root(),        l, p),
                take("project.service_name", lo.serviceName(), hi.serviceName(), l, p),
                takeList("project.modules",  lo.modules(),     hi.modules(),     l, p));
    }

    private IndexingConfig mergeIndexing(IndexingConfig lo, IndexingConfig hi, Input l, Map<String,ConfigProvenance> p) {
        return new IndexingConfig(
                takeList("indexing.languages", lo.languages(), hi.languages(), l, p),
                takeList("indexing.include",   lo.include(),   hi.include(),   l, p),
                takeList("indexing.exclude",   lo.exclude(),   hi.exclude(),   l, p),
                take("indexing.incremental",       lo.incremental(),     hi.incremental(),     l, p),
                take("indexing.cache_dir",         lo.cacheDir(),        hi.cacheDir(),        l, p),
                take("indexing.parallelism",       lo.parallelism(),     hi.parallelism(),     l, p),
                take("indexing.batch_size",        lo.batchSize(),       hi.batchSize(),       l, p),
                take("indexing.max_depth",         lo.maxDepth(),        hi.maxDepth(),        l, p),
                take("indexing.max_radius",        lo.maxRadius(),       hi.maxRadius(),       l, p),
                take("indexing.max_files",         lo.maxFiles(),        hi.maxFiles(),        l, p),
                take("indexing.max_snippet_lines", lo.maxSnippetLines(), hi.maxSnippetLines(), l, p),
                takeList("indexing.parsers",       lo.parsers(),         hi.parsers(),         l, p));
    }

    private ServingConfig mergeServing(ServingConfig lo, ServingConfig hi, Input l, Map<String,ConfigProvenance> p) {
        return new ServingConfig(
                take("serving.port",         lo.port(),        hi.port(),        l, p),
                take("serving.bind_address", lo.bindAddress(), hi.bindAddress(), l, p),
                take("serving.read_only",    lo.readOnly(),    hi.readOnly(),    l, p),
                new Neo4jConfig(
                        take("serving.neo4j.dir",             lo.neo4j().dir(),            hi.neo4j().dir(),            l, p),
                        take("serving.neo4j.page_cache_mb",   lo.neo4j().pageCacheMb(),    hi.neo4j().pageCacheMb(),    l, p),
                        take("serving.neo4j.heap_initial_mb", lo.neo4j().heapInitialMb(),  hi.neo4j().heapInitialMb(),  l, p),
                        take("serving.neo4j.heap_max_mb",     lo.neo4j().heapMaxMb(),      hi.neo4j().heapMaxMb(),      l, p)));
    }

    private McpConfig mergeMcp(McpConfig lo, McpConfig hi, Input l, Map<String,ConfigProvenance> p) {
        return new McpConfig(
                take("mcp.enabled",   lo.enabled(),   hi.enabled(),   l, p),
                take("mcp.transport", lo.transport(), hi.transport(), l, p),
                take("mcp.base_path", lo.basePath(),  hi.basePath(),  l, p),
                new McpAuthConfig(
                        take("mcp.auth.mode",      lo.auth().mode(),     hi.auth().mode(),     l, p),
                        take("mcp.auth.token_env", lo.auth().tokenEnv(), hi.auth().tokenEnv(), l, p)),
                new McpLimitsConfig(
                        take("mcp.limits.per_tool_timeout_ms", lo.limits().perToolTimeoutMs(), hi.limits().perToolTimeoutMs(), l, p),
                        take("mcp.limits.max_results",         lo.limits().maxResults(),       hi.limits().maxResults(),       l, p),
                        take("mcp.limits.max_payload_bytes",   lo.limits().maxPayloadBytes(),  hi.limits().maxPayloadBytes(),  l, p),
                        take("mcp.limits.rate_per_minute",     lo.limits().ratePerMinute(),    hi.limits().ratePerMinute(),    l, p)),
                new McpToolsConfig(
                        takeList("mcp.tools.enabled",  lo.tools().enabled(),  hi.tools().enabled(),  l, p),
                        takeList("mcp.tools.disabled", lo.tools().disabled(), hi.tools().disabled(), l, p)));
    }

    private ObservabilityConfig mergeObservability(ObservabilityConfig lo, ObservabilityConfig hi, Input l, Map<String,ConfigProvenance> p) {
        return new ObservabilityConfig(
                take("observability.metrics",    lo.metrics(),    hi.metrics(),    l, p),
                take("observability.tracing",    lo.tracing(),    hi.tracing(),    l, p),
                take("observability.log_format", lo.logFormat(),  hi.logFormat(),  l, p),
                take("observability.log_level",  lo.logLevel(),   hi.logLevel(),   l, p));
    }

    private DetectorsConfig mergeDetectors(DetectorsConfig lo, DetectorsConfig hi, Input l, Map<String,ConfigProvenance> p) {
        return new DetectorsConfig(
                takeList("detectors.profiles",   lo.profiles(),   hi.profiles(),   l, p),
                takeList("detectors.categories", lo.categories(), hi.categories(), l, p),
                takeList("detectors.include",    lo.include(),    hi.include(),    l, p),
                takeMap("detectors.overrides",   lo.overrides(),  hi.overrides(),  l, p));
    }

    private <T> T take(String path, T lo, T hi, Input l, Map<String, ConfigProvenance> p) {
        if (hi != null) {
            p.put(path, new ConfigProvenance(l.layer(), path, hi, l.sourceLabel()));
            return hi;
        }
        return lo;
    }

    private <T> List<T> takeList(String path, List<T> lo, List<T> hi, Input l, Map<String, ConfigProvenance> p) {
        if (hi != null && !hi.isEmpty()) {
            p.put(path, new ConfigProvenance(l.layer(), path, hi, l.sourceLabel()));
            return hi;
        }
        return lo == null ? List.of() : lo;
    }

    private <K,V> Map<K,V> takeMap(String path, Map<K,V> lo, Map<K,V> hi, Input l, Map<String, ConfigProvenance> p) {
        if (hi != null && !hi.isEmpty()) {
            p.put(path, new ConfigProvenance(l.layer(), path, hi, l.sourceLabel()));
            return hi;
        }
        return lo == null ? Map.of() : lo;
    }
}
