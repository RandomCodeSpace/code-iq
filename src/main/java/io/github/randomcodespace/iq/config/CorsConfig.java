package io.github.randomcodespace.iq.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS configuration for the {@code serving} profile.
 *
 * <p>The serving layer is strictly read-only: clients only need {@code GET} on the REST API
 * and {@code GET}/{@code POST} on the MCP streamable-HTTP endpoint. Mutating verbs
 * ({@code PUT}, {@code PATCH}, {@code DELETE}) are intentionally not allowed —
 * analysis happens locally via the CLI ({@code codeiq index} / {@code codeiq enrich})
 * and the server never accepts data manipulation.
 *
 * <p>Default origin patterns cover the common local-dev cases (loopback on any port).
 * Override via {@code codeiq.cors.allowed-origin-patterns} (CSV) when serving over a
 * trusted network or behind a reverse proxy.
 */
@Configuration
@Profile("serving")
public class CorsConfig {

    /** Default allowed origin patterns: loopback on any port (covers local dev / IDE proxies). */
    static final String DEFAULT_ALLOWED_ORIGIN_PATTERNS =
            "http://localhost:[*],http://127.0.0.1:[*]";

    /** Read-only REST API: only safe / preflight verbs. */
    static final String[] API_ALLOWED_METHODS = {"GET", "OPTIONS"};

    /** MCP streamable-HTTP: GET for SSE/handshake, POST for JSON-RPC frames, OPTIONS for preflight. */
    static final String[] MCP_ALLOWED_METHODS = {"GET", "POST", "OPTIONS"};

    /** Allow all request headers — clients commonly send custom MCP / Auth headers. */
    static final String ALLOWED_HEADERS = "*";

    @Value("${codeiq.cors.allowed-origin-patterns:" + DEFAULT_ALLOWED_ORIGIN_PATTERNS + "}")
    private String allowedOriginPatterns = DEFAULT_ALLOWED_ORIGIN_PATTERNS;

    @Bean
    public WebMvcConfigurer corsConfigurer() {
        String[] patterns = allowedOriginPatterns.split(",");
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                registry.addMapping("/api/**")
                        .allowedOriginPatterns(patterns)
                        .allowedMethods(API_ALLOWED_METHODS)
                        .allowedHeaders(ALLOWED_HEADERS);
                registry.addMapping("/mcp/**")
                        .allowedOriginPatterns(patterns)
                        .allowedMethods(MCP_ALLOWED_METHODS)
                        .allowedHeaders(ALLOWED_HEADERS);
            }
        };
    }
}
