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

        // indexing.parallelism — null means "auto-detect"; any non-null value must be a positive int.
        if (c.indexing().parallelism() != null && c.indexing().parallelism() <= 0)
            errs.add(new ConfigError("indexing.parallelism",
                    "must be > 0 (or unset for auto-detect); got " + c.indexing().parallelism(),
                    "validator"));

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
        Long maxPayload = c.mcp().limits().maxPayloadBytes();
        if (maxPayload != null && maxPayload <= 0)
            errs.add(new ConfigError("mcp.limits.max_payload_bytes", "must be > 0", "validator"));
        Integer ratePerMin = c.mcp().limits().ratePerMinute();
        if (ratePerMin != null && ratePerMin <= 0)
            errs.add(new ConfigError("mcp.limits.rate_per_minute", "must be > 0", "validator"));
        Integer maxDepth = c.mcp().limits().maxDepth();
        if (maxDepth != null) {
            if (maxDepth <= 0)
                errs.add(new ConfigError("mcp.limits.max_depth", "must be > 0", "validator"));
            // Hard ceiling on max_depth — variable-length Cypher with depth >100
            // is almost always either a misconfig or a reconnaissance probe.
            // A graph with 100M nodes and a fan-out of 5 reaches every node by
            // depth 12 anyway; depth >100 is pathological in practice.
            if (maxDepth > 100)
                errs.add(new ConfigError("mcp.limits.max_depth",
                        "must be <= 100 (variable-length Cypher above this depth is "
                                + "pathological); got " + maxDepth, "validator"));
        }

        // mcp.auth.* — blank-string checks for required fields
        // When mcp.auth.mode=bearer, either token_env (env var name) or token
        // (literal config value) must resolve to a non-blank string. The
        // TokenResolver also fail-fasts at startup, but catching this in
        // `codeiq config validate` lets operators see the issue before
        // launching the server.
        if ("bearer".equalsIgnoreCase(c.mcp().auth().mode())) {
            String tokenEnvName = c.mcp().auth().tokenEnv();
            String tokenLiteral = c.mcp().auth().token();
            // Blank means "set but empty" — silently coerced to null at
            // config-read but TokenResolver would still fail. Catch here.
            if (tokenEnvName != null && tokenEnvName.isBlank())
                errs.add(new ConfigError("mcp.auth.token_env",
                        "must be non-blank when set (use unset for default CODEIQ_MCP_TOKEN)",
                        "validator"));
            if (tokenLiteral != null && tokenLiteral.isBlank())
                errs.add(new ConfigError("mcp.auth.token",
                        "must be non-blank when set", "validator"));
        }

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
