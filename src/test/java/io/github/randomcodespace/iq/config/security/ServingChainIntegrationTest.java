package io.github.randomcodespace.iq.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import io.github.randomcodespace.iq.config.unified.ConfigDefaults;
import io.github.randomcodespace.iq.config.unified.McpAuthConfig;
import io.github.randomcodespace.iq.config.unified.McpConfig;
import io.github.randomcodespace.iq.config.unified.McpLimitsConfig;
import io.github.randomcodespace.iq.config.unified.McpToolsConfig;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end test of the serving filter chain wired in the order
 * {@link io.github.randomcodespace.iq.config.security.SecurityConfig}
 * registers it: RequestIdFilter → SecurityHeadersFilter → RateLimitFilter
 * → BearerAuthFilter. Validates the cross-filter contract (X-Request-Id
 * echo, 401 envelope, 429 envelope, security headers, rate-limit token
 * bucket isolation per client) without spinning up Spring or Neo4j.
 *
 * <p>This is the "serving chain integration" gap — pre-PR-5 each filter
 * had unit-test coverage in isolation but no test exercised the full
 * chain together. A regression that breaks header propagation between
 * RequestIdFilter and downstream filters would slip through unit tests.
 *
 * <p>Lives in the {@code io.github.randomcodespace.iq.config.security}
 * package so it can call package-private {@link TokenResolver#resolve()}.
 */
class ServingChainIntegrationTest {

    private static final String TEST_TOKEN = "test-bearer-token-12345-abcdef";

    private RequestIdFilter requestIdFilter;
    private SecurityHeadersFilter securityHeadersFilter;
    private RateLimitFilter rateLimitFilter;
    private BearerAuthFilter bearerAuthFilter;

    @BeforeEach
    void setUp() {
        // Build a minimal config: bearer auth + 5 req/min so we can exercise
        // the rate-limit path quickly without timing out the test.
        McpAuthConfig auth = new McpAuthConfig("bearer", null, TEST_TOKEN, null);
        McpLimitsConfig limits = new McpLimitsConfig(15_000, 500, 2_000_000L, 5, 10);
        McpConfig mcp = new McpConfig(true, "http", null, auth, limits, McpToolsConfig.empty());
        CodeIqUnifiedConfig defaults = ConfigDefaults.builtIn();
        CodeIqUnifiedConfig config = new CodeIqUnifiedConfig(
                defaults.project(),
                defaults.indexing(),
                defaults.serving(),
                mcp,
                defaults.observability(),
                defaults.detectors());

        // Active `serving` profile so TokenResolver doesn't fail-fast on
        // mode=none (it would also fail-fast on mode=bearer with no token,
        // but we configured a token).
        StandardEnvironment env = new StandardEnvironment();
        env.setActiveProfiles("serving");
        TokenResolver tokenResolver = new TokenResolver(config, env);
        tokenResolver.resolve();

        requestIdFilter = new RequestIdFilter();
        securityHeadersFilter = new SecurityHeadersFilter();
        rateLimitFilter = new RateLimitFilter(config);
        bearerAuthFilter = new BearerAuthFilter(tokenResolver);
    }

    private MockHttpServletResponse runChain(String method, String uri, Map<String, String> headers)
            throws ServletException, IOException {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        if (headers != null) headers.forEach(req::addHeader);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        // Run through the chain in the same order SecurityConfig registers them.
        FilterChain terminal = (r, s) -> ((HttpServletResponse) s).setStatus(200);
        FilterChain c4 = (r, s) -> bearerAuthFilter.doFilter(r, s, terminal);
        FilterChain c3 = (r, s) -> rateLimitFilter.doFilter(r, s, c4);
        FilterChain c2 = (r, s) -> securityHeadersFilter.doFilter(r, s, c3);
        FilterChain c1 = (r, s) -> requestIdFilter.doFilter(r, s, c2);
        c1.doFilter(req, resp);
        return resp;
    }

    @Test
    void requestWithoutAuthGets401WithEnvelope() throws Exception {
        MockHttpServletResponse resp = runChain("GET", "/api/stats", Map.of());

        assertEquals(401, resp.getStatus());
        assertEquals("Bearer realm=\"codeiq\"", resp.getHeader("WWW-Authenticate"));
        Map<?, ?> body = new ObjectMapper().readValue(resp.getContentAsByteArray(), Map.class);
        assertEquals("UNAUTHORIZED", body.get("code"));
        assertEquals("Bearer token required.", body.get("message"));
        assertNotNull(body.get("request_id"));
        // The same request_id is echoed in X-Request-Id response header so
        // operators can grep their JSON logs for the matching log line.
        assertEquals(body.get("request_id"), resp.getHeader("X-Request-Id"));
    }

    @Test
    void requestWithValidTokenPassesThrough() throws Exception {
        MockHttpServletResponse resp = runChain("GET", "/api/stats",
                Map.of("Authorization", "Bearer " + TEST_TOKEN));

        assertEquals(200, resp.getStatus());
        assertNotNull(resp.getHeader("X-Request-Id"),
                "Successful requests must also receive a correlation ID");
        assertNotNull(resp.getHeader("X-RateLimit-Remaining"),
                "Rate-limit visibility headers must accompany every authed response");
    }

    @Test
    void requestWithWrongTokenGets401() throws Exception {
        MockHttpServletResponse resp = runChain("GET", "/api/stats",
                Map.of("Authorization", "Bearer wrong-token-bytes-here-please"));

        assertEquals(401, resp.getStatus());
    }

    @Test
    void securityHeadersArePresentOnSuccessResponses() throws Exception {
        MockHttpServletResponse resp = runChain("GET", "/api/stats",
                Map.of("Authorization", "Bearer " + TEST_TOKEN));

        // SecurityHeadersFilter runs before auth, so headers appear on every
        // response — exact set varies by filter impl but X-Content-Type-Options
        // and X-Frame-Options are baseline.
        assertNotNull(resp.getHeader("X-Content-Type-Options"));
        assertNotNull(resp.getHeader("X-Frame-Options"));
        assertNotNull(resp.getHeader("Referrer-Policy"));
    }

    @Test
    void inboundRequestIdHeaderIsPropagated() throws Exception {
        String upstream = "abc-trace-12345";
        MockHttpServletResponse resp = runChain("GET", "/api/stats",
                Map.of("Authorization", "Bearer " + TEST_TOKEN,
                        "X-Request-Id", upstream));

        assertEquals(upstream, resp.getHeader("X-Request-Id"),
                "Valid upstream X-Request-Id must propagate end-to-end");
    }

    @Test
    void inboundRequestIdWithControlCharsIsRejectedAndReplaced() throws Exception {
        // Log-injection attempt — newline embedded in upstream header
        String malicious = "abc\nINFO: granted access";
        MockHttpServletResponse resp = runChain("GET", "/api/stats",
                Map.of("Authorization", "Bearer " + TEST_TOKEN,
                        "X-Request-Id", malicious));

        String emitted = resp.getHeader("X-Request-Id");
        assertNotNull(emitted);
        assertNotEquals(malicious, emitted);
        assertFalse(emitted.contains("\n"),
                "Sanitized request_id must never contain control characters");
    }

    @Test
    void rateLimitFiresAfterCapacityWith429Envelope() throws Exception {
        // Configured 5 req/min above. Send 5 OK requests, the 6th should be 429.
        Map<String, String> auth = Map.of("Authorization", "Bearer " + TEST_TOKEN);
        for (int i = 0; i < 5; i++) {
            MockHttpServletResponse ok = runChain("GET", "/api/stats", auth);
            assertEquals(200, ok.getStatus(), "Request " + i + " must pass under cap");
        }
        MockHttpServletResponse limited = runChain("GET", "/api/stats", auth);
        assertEquals(429, limited.getStatus(),
                "Sixth request beyond per-minute capacity must be 429");
        assertNotNull(limited.getHeader("Retry-After"),
                "429 response must include Retry-After (RFC 6585 §4)");
        assertEquals("0", limited.getHeader("X-RateLimit-Remaining"));
        Map<?, ?> body = new ObjectMapper().readValue(limited.getContentAsByteArray(), Map.class);
        assertEquals("RATE_LIMITED", body.get("code"));
        assertNotNull(body.get("request_id"));
    }

    @Test
    void healthEndpointBypassesAuth() throws Exception {
        // shouldNotFilter() in BearerAuthFilter excludes /actuator/health/* —
        // verify the chain returns 200 without an Authorization header
        // (kubelet probes don't carry bearer tokens).
        MockHttpServletResponse resp = runChain("GET", "/actuator/health", Map.of());

        assertEquals(200, resp.getStatus(),
                "Health probe must succeed without auth");
    }

    @Test
    void rateLimitBucketsAreIsolatedPerToken() throws Exception {
        // First token exhausts its bucket
        Map<String, String> tokenA = Map.of("Authorization", "Bearer " + TEST_TOKEN);
        for (int i = 0; i < 5; i++) runChain("GET", "/api/stats", tokenA);
        MockHttpServletResponse limitedA = runChain("GET", "/api/stats", tokenA);
        assertEquals(429, limitedA.getStatus());

        // Second token (different client) gets its own fresh bucket. The
        // rate-limit filter keys by SHA-256(Authorization), so a different
        // header value = different bucket. The request is admitted by the
        // bucket and reaches the auth filter, which rejects with 401.
        Map<String, String> tokenB = Map.of("Authorization", "Bearer different-token-but-formatted-ok");
        MockHttpServletResponse respB = runChain("GET", "/api/stats", tokenB);
        assertEquals(401, respB.getStatus(),
                "Different token = different bucket; rate-limit must not bleed across clients");
    }
}
