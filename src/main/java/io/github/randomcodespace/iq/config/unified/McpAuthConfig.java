package io.github.randomcodespace.iq.config.unified;

/**
 * MCP authentication configuration.
 *
 * <p>{@code mode} selects the authentication scheme. Supported values:
 * <ul>
 *   <li>{@code none} — no auth. <strong>Default.</strong> Built-in defaults set
 *       {@code allowUnauthenticated=true} so the server starts unauthenticated
 *       out of the box. Operators who want hard-fail can override
 *       {@code mcp.auth.allow_unauthenticated: false} explicitly; the resolver
 *       will then refuse to start under the {@code serving} profile.</li>
 *   <li>{@code bearer} — opaque bearer token. Recommended for production.
 *       Source priority: {@code CODEIQ_MCP_TOKEN} env var > {@code token} field
 *       below > startup failure.</li>
 *   <li>{@code mtls} — reserved; not yet wired (tracked under follow-up).</li>
 * </ul>
 *
 * <p>{@code tokenEnv} is the env-var name to read the token from (defaults to
 * {@code CODEIQ_MCP_TOKEN} when null). {@code token} is a fallback in-config token —
 * not recommended for production (use the env var + a Kubernetes Secret); allowed for
 * local development. {@code allowUnauthenticated} is the explicit acknowledgement
 * flag for {@code mode=none} in serving — defaulted to {@code true} via
 * {@code ConfigDefaults} so a fresh install just works; override to {@code false}
 * to make {@code mode=none} a fail-fast misconfiguration in serving.
 */
public record McpAuthConfig(
        String mode,
        String tokenEnv,
        String token,
        Boolean allowUnauthenticated) {
    public static McpAuthConfig empty() { return new McpAuthConfig(null, null, null, null); }
}
