package io.github.randomcodespace.iq.web;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Catch-all controller that forwards unmatched routes to index.html
 * for React Router client-side routing (HTML5 pushState).
 * <p>
 * Only matches paths without a file extension (e.g. /graph, /explorer/class)
 * so static assets (.js, .css, .html, .svg) are served normally.
 * <p>
 * Disabled when {@code codeiq.ui.enabled=false} (i.e. {@code --no-ui} flag passed to serve).
 */
@Controller
@Profile("serving")
@ConditionalOnProperty(name = "codeiq.ui.enabled", havingValue = "true", matchIfMissing = true)
public class SpaController {

    @GetMapping(value = {
            "/graph",
            "/graph/**",
            "/explorer",
            "/explorer/**",
            "/console",
            "/console/**",
            "/api-docs",
            "/dashboard",
            "/dashboard/**"
    })
    public String forward() {
        return "forward:/index.html";
    }

    /**
     * Catch-all for any React Router paths not explicitly listed above.
     * Matches single-segment paths without a file extension (no dot).
     * Does NOT match /api/**, /mcp/**, /actuator/**, or static assets.
     */
    @GetMapping("/{path:[^\\.]*}")
    public String catchAll() {
        return "forward:/index.html";
    }
}
