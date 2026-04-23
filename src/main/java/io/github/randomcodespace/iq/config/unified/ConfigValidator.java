package io.github.randomcodespace.iq.config.unified;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Validates a merged CodeIqUnifiedConfig. Uses explicit checks rather than
 * jakarta.validation annotations because records with inherited nullability
 * and enum-like string fields are awkward to express via bean-validation
 * alone. The explicit approach also keeps the error messages actionable.
 */
public final class ConfigValidator {

    private static final Set<String> MCP_TRANSPORTS = Set.of("http", "stdio");
    private static final Set<String> MCP_AUTH_MODES = Set.of("none", "bearer", "mtls");
    private static final Set<String> LOG_FORMATS = Set.of("json", "text");
    private static final Set<String> LOG_LEVELS = Set.of("trace", "debug", "info", "warn", "error");

    public List<ConfigError> validate(CodeIqUnifiedConfig c) {
        List<ConfigError> errs = new ArrayList<>();

        // serving.port
        if (c.serving().port() != null && (c.serving().port() < 1 || c.serving().port() > 65535)) {
            errs.add(new ConfigError("serving.port",
                    "port must be 1-65535; got " + c.serving().port(), "validator"));
        }

        // serving.neo4j.*_mb
        Integer pc = c.serving().neo4j().pageCacheMb();
        Integer hi = c.serving().neo4j().heapInitialMb();
        Integer hm = c.serving().neo4j().heapMaxMb();
        if (pc != null && pc < 0) errs.add(new ConfigError("serving.neo4j.page_cache_mb", "must be >= 0", "validator"));
        if (hi != null && hi < 0) errs.add(new ConfigError("serving.neo4j.heap_initial_mb", "must be >= 0", "validator"));
        if (hm != null && hm < 0) errs.add(new ConfigError("serving.neo4j.heap_max_mb", "must be >= 0", "validator"));
        if (hi != null && hm != null && hi > hm)
            errs.add(new ConfigError("serving.neo4j.heap_initial_mb",
                    "heap_initial_mb (" + hi + ") must be <= heap_max_mb (" + hm + ")", "validator"));

        // indexing.batch_size
        if (c.indexing().batchSize() != null && c.indexing().batchSize() <= 0)
            errs.add(new ConfigError("indexing.batch_size", "must be > 0", "validator"));

        // mcp.transport
        if (c.mcp().transport() != null && !MCP_TRANSPORTS.contains(c.mcp().transport()))
            errs.add(new ConfigError("mcp.transport",
                    "must be one of " + MCP_TRANSPORTS + "; got " + c.mcp().transport(), "validator"));

        // mcp.auth.mode
        if (c.mcp().auth().mode() != null && !MCP_AUTH_MODES.contains(c.mcp().auth().mode()))
            errs.add(new ConfigError("mcp.auth.mode",
                    "must be one of " + MCP_AUTH_MODES + "; got " + c.mcp().auth().mode(), "validator"));

        // mcp.limits.*
        Integer perTool = c.mcp().limits().perToolTimeoutMs();
        if (perTool != null && perTool <= 0)
            errs.add(new ConfigError("mcp.limits.per_tool_timeout_ms", "must be > 0", "validator"));
        Integer maxRes = c.mcp().limits().maxResults();
        if (maxRes != null && maxRes <= 0)
            errs.add(new ConfigError("mcp.limits.max_results", "must be > 0", "validator"));

        // observability.log_format / log_level
        if (c.observability().logFormat() != null && !LOG_FORMATS.contains(c.observability().logFormat()))
            errs.add(new ConfigError("observability.log_format",
                    "must be one of " + LOG_FORMATS + "; got " + c.observability().logFormat(), "validator"));
        if (c.observability().logLevel() != null
                && !LOG_LEVELS.contains(c.observability().logLevel().toLowerCase()))
            errs.add(new ConfigError("observability.log_level",
                    "must be one of " + LOG_LEVELS + "; got " + c.observability().logLevel(), "validator"));

        return errs;
    }
}
