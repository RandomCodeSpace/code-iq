package io.github.randomcodespace.iq.config.unified;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Folds CODEIQ_<SECTION>_<KEY> environment variables into a CodeIqUnifiedConfig
 * overlay. Unknown variable names are ignored (forward-compatible with new
 * sections). Type mismatches (e.g. non-numeric port) throw ConfigLoadException
 * with the variable name in the message.
 *
 * Mapping rule: strip CODEIQ_ prefix, lowercase, split by "_", and walk the
 * record tree. Dotted names are not supported (use separate _ segments).
 */
public final class EnvVarOverlay {
    private EnvVarOverlay() {}

    public static CodeIqUnifiedConfig from(Map<String, String> env) {
        Integer port = null, batch = null, perToolMs = null, maxResults = null, ratePerMin = null,
                pageMb = null, heapInit = null, heapMax = null,
                maxDepth = null, maxRadius = null, maxFiles = null, maxSnippetLines = null;
        Long maxPayload = null;
        Boolean readOnly = null, incremental = null, metrics = null, tracing = null, mcpEnabled = null;
        String cacheDir = null, bindAddr = null, projectName = null, projectRoot = null,
                projectServiceName = null,
                neo4jDir = null, mcpTransport = null, mcpBasePath = null, mcpMode = null,
                mcpTokenEnv = null, logFormat = null, logLevel = null, parallelism = null;
        List<String> languages = List.of(), include = List.of(), exclude = List.of(),
                     toolsEnabled = List.of(), toolsDisabled = List.of(), profiles = List.of();

        for (var e : env.entrySet()) {
            String k = e.getKey(), v = e.getValue();
            if (!k.startsWith("CODEIQ_")) continue;
            String key = k.substring("CODEIQ_".length());
            try {
                switch (key) {
                    case "PROJECT_NAME" -> projectName = v;
                    case "PROJECT_ROOT" -> projectRoot = v;
                    case "PROJECT_SERVICE_NAME" -> projectServiceName = v;
                    case "INDEXING_LANGUAGES" -> languages = splitCsv(v);
                    case "INDEXING_INCLUDE" -> include = splitCsv(v);
                    case "INDEXING_EXCLUDE" -> exclude = splitCsv(v);
                    case "INDEXING_INCREMENTAL" -> incremental = Boolean.parseBoolean(v);
                    case "INDEXING_CACHEDIR" -> cacheDir = v;
                    case "INDEXING_PARALLELISM" -> parallelism = v;
                    case "INDEXING_BATCHSIZE" -> batch = Integer.parseInt(v);
                    case "INDEXING_MAX_DEPTH" -> maxDepth = Integer.parseInt(v);
                    case "INDEXING_MAX_RADIUS" -> maxRadius = Integer.parseInt(v);
                    case "INDEXING_MAX_FILES" -> maxFiles = Integer.parseInt(v);
                    case "INDEXING_MAX_SNIPPET_LINES" -> maxSnippetLines = Integer.parseInt(v);
                    case "SERVING_PORT" -> port = Integer.parseInt(v);
                    case "SERVING_BINDADDRESS" -> bindAddr = v;
                    case "SERVING_READONLY" -> readOnly = Boolean.parseBoolean(v);
                    case "SERVING_NEO4J_DIR" -> neo4jDir = v;
                    case "SERVING_NEO4J_PAGECACHEMB" -> pageMb = Integer.parseInt(v);
                    case "SERVING_NEO4J_HEAPINITIALMB" -> heapInit = Integer.parseInt(v);
                    case "SERVING_NEO4J_HEAPMAXMB" -> heapMax = Integer.parseInt(v);
                    case "MCP_ENABLED" -> mcpEnabled = Boolean.parseBoolean(v);
                    case "MCP_TRANSPORT" -> mcpTransport = v;
                    case "MCP_BASEPATH" -> mcpBasePath = v;
                    case "MCP_AUTH_MODE" -> mcpMode = v;
                    case "MCP_AUTH_TOKENENV" -> mcpTokenEnv = v;
                    case "MCP_LIMITS_PERTOOLTIMEOUTMS" -> perToolMs = Integer.parseInt(v);
                    case "MCP_LIMITS_MAXRESULTS" -> maxResults = Integer.parseInt(v);
                    case "MCP_LIMITS_MAXPAYLOADBYTES" -> maxPayload = Long.parseLong(v);
                    case "MCP_LIMITS_RATEPERMINUTE" -> ratePerMin = Integer.parseInt(v);
                    case "MCP_TOOLS_ENABLED" -> toolsEnabled = splitCsv(v);
                    case "MCP_TOOLS_DISABLED" -> toolsDisabled = splitCsv(v);
                    case "OBSERVABILITY_METRICS" -> metrics = Boolean.parseBoolean(v);
                    case "OBSERVABILITY_TRACING" -> tracing = Boolean.parseBoolean(v);
                    case "OBSERVABILITY_LOGFORMAT" -> logFormat = v;
                    case "OBSERVABILITY_LOGLEVEL" -> logLevel = v;
                    case "DETECTORS_PROFILES" -> profiles = splitCsv(v);
                    default -> { /* unknown key — ignore, forward-compatible */ }
                }
            } catch (NumberFormatException nfe) {
                throw new ConfigLoadException(
                        "Env var " + k + " must be numeric; got '" + v + "'", nfe);
            }
        }

        return new CodeIqUnifiedConfig(
                new ProjectConfig(projectName, projectRoot, projectServiceName, List.of()),
                new IndexingConfig(languages, include, exclude, incremental, cacheDir, parallelism, batch,
                        maxDepth, maxRadius, maxFiles, maxSnippetLines),
                new ServingConfig(port, bindAddr, readOnly,
                        new Neo4jConfig(neo4jDir, pageMb, heapInit, heapMax)),
                new McpConfig(mcpEnabled, mcpTransport, mcpBasePath,
                        new McpAuthConfig(mcpMode, mcpTokenEnv),
                        new McpLimitsConfig(perToolMs, maxResults, maxPayload, ratePerMin),
                        new McpToolsConfig(toolsEnabled, toolsDisabled)),
                new ObservabilityConfig(metrics, tracing, logFormat, logLevel),
                new DetectorsConfig(profiles, Map.of())
        );
    }

    private static List<String> splitCsv(String v) {
        if (v == null || v.isBlank()) return List.of();
        return Arrays.stream(v.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
