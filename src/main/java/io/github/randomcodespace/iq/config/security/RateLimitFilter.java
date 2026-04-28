package io.github.randomcodespace.iq.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import io.github.randomcodespace.iq.config.unified.McpLimitsConfig;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-client token-bucket rate limiter on {@code /api/**} and {@code /mcp/**}.
 *
 * <p>Audit #3 (HIGH) — without this, one MCP client in a runaway loop could
 * fire {@code find_cycles} or {@code blast_radius} at hundreds of QPS,
 * saturating the embedded Neo4j page cache and starving the readiness probe
 * until kubelet restarts the pod.
 *
 * <p><b>Key derivation:</b> SHA-256 hash of the {@code Authorization} header
 * when present (so the token value never lives in our key map), otherwise the
 * remote IP. Hashing the header value also means the rate limiter pre-auth
 * (it can throttle bad-token spammers without needing to know the token is
 * invalid).
 *
 * <p><b>Filter order:</b> registered before {@link BearerAuthFilter} so an
 * unauthenticated brute-force attempt also gets throttled. This is why we key
 * on the hashed header value rather than the {@code Authentication} principal.
 *
 * <p><b>Bucket semantics:</b> capacity = {@code rate_per_minute}, refill =
 * the same number per minute, greedy refill (one token per
 * {@code 60s / rate_per_minute}). A burst up to capacity is allowed; sustained
 * over-rate gets HTTP 429 with a {@code Retry-After: <seconds>} header.
 *
 * <p><b>Memory:</b> one Bucket per distinct key. Buckets are stored in a
 * {@link ConcurrentHashMap}; in production this is bounded by
 * {@code num_distinct_clients}, which for codeiq's intended ops shape (single-
 * tenant pod, a handful of agents) is small. If multi-tenant exposure is ever
 * added, swap to a Caffeine cache with a max-size eviction policy.
 */
@Component
@Profile("serving")
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Default = audit-recommended 300/min (5 QPS sustained, burst up to 300). */
    static final int DEFAULT_RATE_PER_MINUTE = 300;

    private final long ratePerMinute;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(CodeIqUnifiedConfig unifiedConfig) {
        McpLimitsConfig lim = (unifiedConfig != null && unifiedConfig.mcp() != null)
                ? unifiedConfig.mcp().limits() : McpLimitsConfig.empty();
        Integer rate = lim.ratePerMinute();
        this.ratePerMinute = (rate != null && rate > 0) ? rate : DEFAULT_RATE_PER_MINUTE;
        log.info("RateLimitFilter: {} requests/minute per client", this.ratePerMinute);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Same permit list as BearerAuthFilter — health probes + static assets
        // shouldn't be rate-limited (they're high-frequency from kubelet itself).
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
        String key = clientKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(limit -> limit
                        .capacity(ratePerMinute)
                        .refillGreedy(ratePerMinute, Duration.ofMinutes(1)))
                .build());

        var probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Limit", String.valueOf(ratePerMinute));
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }

        // Rate limited.
        long retryAfterSec = Math.max(1L,
                Duration.ofNanos(probe.getNanosToWaitForRefill()).toSeconds());
        String requestId = currentRequestId();
        // CWE-117 / CodeQL java/log-injection: request method and URI flow
        // from untrusted client headers; sanitize before logging via
        // BearerAuthFilter.sanitizeForLog (strips \r\n\t with explicit
        // single-char replace chains — the pattern CodeQL recognizes).
        log.warn("Rate-limited: {} {} (request_id={}, retry_after={}s)",
                BearerAuthFilter.sanitizeForLog(request.getMethod()),
                BearerAuthFilter.sanitizeForLog(request.getRequestURI()),
                requestId, retryAfterSec);
        // 429 — jakarta.servlet doesn't define a constant for this in all versions.
        response.setStatus(429);
        response.setHeader("Retry-After", String.valueOf(retryAfterSec));
        response.setHeader("X-RateLimit-Limit", String.valueOf(ratePerMinute));
        response.setHeader("X-RateLimit-Remaining", "0");
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = Map.of(
                "code", "RATE_LIMITED",
                "message", "Too many requests. Retry after " + retryAfterSec + " seconds.",
                "request_id", requestId);
        JSON.writeValue(response.getOutputStream(), body);
    }

    /**
     * Derive a per-client key. SHA-256 hash of the {@code Authorization}
     * header when present (so the token value never lives in our map), else
     * fall back to {@code X-Forwarded-For} (first hop) → {@code RemoteAddr}.
     */
    static String clientKey(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth != null && !auth.isBlank()) {
            return "auth:" + sha256Short(auth);
        }
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return "ip:" + (comma > 0 ? xff.substring(0, comma).trim() : xff.trim());
        }
        return "ip:" + (request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown");
    }

    /** First 16 hex chars of SHA-256(input) — enough collision resistance for keying. */
    private static String sha256Short(String input) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String currentRequestId() {
        String id = MDC.get("request_id");
        return id != null ? id : UUID.randomUUID().toString();
    }
}
