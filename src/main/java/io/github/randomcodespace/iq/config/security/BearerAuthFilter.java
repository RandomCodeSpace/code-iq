package io.github.randomcodespace.iq.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Validates {@code Authorization: Bearer <token>} on requests to {@code /api/**}
 * and {@code /mcp/**}. Bypasses static-asset / health-probe paths via
 * {@link #shouldNotFilter(HttpServletRequest)}.
 *
 * <p><b>Constant-time compare.</b> Both the provided token and the expected token
 * are first hashed with SHA-256, then compared via {@link MessageDigest#isEqual}.
 * SHA-256 always produces a 32-byte digest, so {@code isEqual} runs over fixed-size
 * byte arrays and the length-oracle that makes raw {@code isEqual} unsafe across
 * mismatched-length inputs cannot be exploited.
 *
 * <p><b>Logging discipline.</b> The {@code Authorization} header value is never
 * passed to a logger from this class. Only the request method and path appear in
 * the rejection log line.
 *
 * <p><b>Scheme matching.</b> RFC 7235 §2.1 says auth schemes are case-insensitive.
 * {@code "Bearer"}, {@code "bearer"}, and any case variant are accepted.
 */
@Component
@Profile("serving")
public class BearerAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerAuthFilter.class);
    static final String SCHEME_PREFIX = "bearer ";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TokenResolver tokenResolver;

    public BearerAuthFilter(TokenResolver tokenResolver) {
        this.tokenResolver = tokenResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String p = request.getRequestURI();
        return "/".equals(p)
                || "/index.html".equals(p)
                || "/favicon.ico".equals(p)
                || (p != null && p.startsWith("/assets/"))
                || (p != null && p.startsWith("/static/"))
                || "/error".equals(p)
                || "/actuator/health".equals(p)
                || "/actuator/health/liveness".equals(p)
                || "/actuator/health/readiness".equals(p);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!tokenResolver.isAuthRequired()) {
            // mode=none with allow_unauthenticated=true. Set a fake
            // authenticated principal so /api/**, /mcp/**, /actuator/**
            // (all `.authenticated()` in SecurityConfig) actually pass.
            // Without this, the bypass branch is inert because the chain
            // still requires a Principal and `.anonymous()` is disabled.
            var auth = new PreAuthenticatedAuthenticationToken(
                    "anonymous-mcp-client", "N/A",
                    List.of(new SimpleGrantedAuthority("ROLE_MCP_CLIENT")));
            auth.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(auth);
            try {
                chain.doFilter(request, response);
            } finally {
                SecurityContextHolder.clearContext();
            }
            return;
        }

        String header = request.getHeader("Authorization");
        if (!isValidToken(header, tokenResolver.expectedTokenBytes())) {
            String requestId = currentRequestId();
            // CRITICAL: never log the Authorization header value. Method and
            // URI are sanitized with sanitizeForLog (strips \r\n\t — defends
            // against CWE-117 log forging via crafted URIs; CodeQL
            // java/log-injection). A request line like
            // `GET /\nINFO: granted access HTTP/1.1` can't inject fake log lines.
            log.warn("Auth rejected: {} {} (request_id={})",
                    sanitizeForLog(request.getMethod()),
                    sanitizeForLog(request.getRequestURI()),
                    requestId);
            sendUnauthorized(response, requestId);
            return;
        }

        var auth = new PreAuthenticatedAuthenticationToken(
                "mcp-client", "N/A",
                List.of(new SimpleGrantedAuthority("ROLE_MCP_CLIENT")));
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /**
     * Constant-time bearer token validation. See class-level Javadoc for the
     * SHA-256 pre-hash rationale.
     */
    static boolean isValidToken(String authorizationHeader, byte[] expectedTokenBytes) {
        if (authorizationHeader == null || expectedTokenBytes == null) return false;
        String lower = authorizationHeader.toLowerCase(Locale.ROOT);
        if (!lower.startsWith(SCHEME_PREFIX)) return false;
        String provided = authorizationHeader.substring(SCHEME_PREFIX.length()).strip();
        if (provided.isEmpty()) return false;
        byte[] providedBytes = provided.getBytes(StandardCharsets.UTF_8);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] providedHash = digest.digest(providedBytes);
            digest.reset();
            byte[] expectedHash = digest.digest(expectedTokenBytes);
            return MessageDigest.isEqual(providedHash, expectedHash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable in JDK", e);
        }
    }

    private void sendUnauthorized(HttpServletResponse resp, String requestId) throws IOException {
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json;charset=UTF-8");
        resp.setHeader("WWW-Authenticate", "Bearer realm=\"codeiq\"");
        Map<String, Object> body = Map.of(
                "code", "UNAUTHORIZED",
                "message", "Bearer token required.",
                "request_id", requestId);
        JSON.writeValue(resp.getOutputStream(), body);
    }

    private static String currentRequestId() {
        String id = MDC.get("request_id");
        return id != null ? id : UUID.randomUUID().toString();
    }

    /**
     * Strip CR/LF/TAB before sending request-derived data to a log appender.
     * Defends against log forging via crafted URIs (CWE-117 / CodeQL
     * {@code java/log-injection}). Explicit single-char replace chains are
     * the pattern CodeQL's standard sanitizer-recognizer matches against
     * — {@code replaceAll("[\p{Cntrl}]", ...)} was not picked up by the
     * data-flow analysis. Output is also length-capped at 256 chars to
     * prevent log-bomb URIs.
     */
    static String sanitizeForLog(String s) {
        if (s == null) return "null";
        String capped = s.length() > 256 ? s.substring(0, 256) + "..." : s;
        return capped.replace("\r", "_").replace("\n", "_").replace("\t", "_");
    }
}
