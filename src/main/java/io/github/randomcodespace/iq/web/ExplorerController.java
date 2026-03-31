package io.github.randomcodespace.iq.web;

import io.github.randomcodespace.iq.query.QueryService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Map;

/**
 * Thymeleaf-based web UI controller for exploring the code knowledge graph.
 * Only active when the "serving" profile is enabled (i.e. during {@code osscodeiq serve}).
 *
 * <p>Full-page routes live under {@code /ui}, HTMX fragment routes under {@code /ui/fragments}.
 */
@Controller
@Profile("serving")
@RequestMapping("/ui")
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "codeiq.neo4j.enabled", havingValue = "true", matchIfMissing = true)
public class ExplorerController {

    private final QueryService queryService;

    public ExplorerController(QueryService queryService) {
        this.queryService = queryService;
    }

    // ---- Full-page routes ----

    @GetMapping({"", "/"})
    public String index(Model model) {
        model.addAttribute("stats", queryService.getStats());
        model.addAttribute("kinds", queryService.listKinds());
        return "explorer/index";
    }

    @GetMapping("/kinds/{kind}")
    public String nodesByKind(
            @PathVariable String kind,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            Model model) {
        model.addAttribute("result", queryService.nodesByKind(kind, limit, offset));
        model.addAttribute("kind", kind);
        return "explorer/nodes";
    }

    @GetMapping("/node")
    public String nodeDetail(@RequestParam String nodeId, Model model) {
        Map<String, Object> detail = queryService.nodeDetailWithEdges(nodeId);
        model.addAttribute("detail", detail);
        return "explorer/detail";
    }

    // ---- HTMX fragment routes ----

    @GetMapping("/fragments/kinds")
    public String kindsFragment(Model model) {
        model.addAttribute("kinds", queryService.listKinds());
        return "explorer/fragments/kinds-grid";
    }

    @GetMapping("/fragments/nodes/{kind}")
    public String nodesFragment(
            @PathVariable String kind,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            Model model) {
        model.addAttribute("result", queryService.nodesByKind(kind, limit, offset));
        model.addAttribute("kind", kind);
        return "explorer/fragments/nodes-grid";
    }

    @GetMapping("/fragments/detail")
    public String detailFragment(@RequestParam String nodeId, Model model) {
        Map<String, Object> detail = queryService.nodeDetailWithEdges(nodeId);
        model.addAttribute("detail", detail);
        return "explorer/fragments/detail-panel";
    }

    @GetMapping("/fragments/search")
    public String searchFragment(
            @RequestParam String q,
            @RequestParam(defaultValue = "50") int limit,
            Model model) {
        List<Map<String, Object>> results = queryService.searchGraph(q, limit);
        model.addAttribute("results", results);
        model.addAttribute("query", q);
        return "explorer/fragments/search-results";
    }

    @GetMapping("/fragments/breadcrumb")
    public String breadcrumbFragment(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String nodeId,
            @RequestParam(required = false) String nodeLabel,
            Model model) {
        model.addAttribute("kind", kind);
        model.addAttribute("nodeId", nodeId);
        model.addAttribute("nodeLabel", nodeLabel);
        return "explorer/fragments/breadcrumb";
    }
}
