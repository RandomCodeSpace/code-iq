package io.github.randomcodespace.iq.web;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Catch-all controller that forwards unmatched routes to index.html
 * for React Router client-side routing (HTML5 pushState).
 * <p>
 * Only matches paths without a file extension (e.g. /topology, /explorer/class)
 * so static assets (.js, .css, .html, .svg) are served normally.
 */
@Controller
@Profile("serving")
public class SpaController {

    @GetMapping(value = {
            "/topology",
            "/topology/**",
            "/explorer",
            "/explorer/**",
            "/flow",
            "/flow/**",
            "/console",
            "/console/**",
            "/api-docs"
    })
    public String forward() {
        return "forward:/index.html";
    }
}
