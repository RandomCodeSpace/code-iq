package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.query.TopologyService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * REST API controller for service topology queries.
 */
@RestController
@RequestMapping("/api/topology")
@Profile("serving")
public class TopologyController {

    private final TopologyService topologyService;
    private final CodeIqConfig config;
    private volatile List<CodeNode> cachedNodes;
    private volatile List<CodeEdge> cachedEdges;

    public TopologyController(TopologyService topologyService, CodeIqConfig config) {
        this.topologyService = topologyService;
        this.config = config;
    }

    // --- H2 in-memory cache: load once, reuse across requests ---

    /**
     * Ensure the H2 cache is loaded into memory. Thread-safe via synchronized.
     */
    private synchronized void ensureCacheLoaded() {
        if (cachedNodes != null) return;
        Path root = Path.of(config.getRootPath()).toAbsolutePath().normalize();
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        Path h2File = root.resolve(config.getCacheDir()).resolve("analysis-cache.mv.db");
        if (!Files.exists(h2File)) return;
        try (AnalysisCache cache = new AnalysisCache(cachePath)) {
            cachedNodes = cache.loadAllNodes();
            cachedEdges = cache.loadAllEdges();
        }
    }

    /**
     * Invalidate the in-memory cache (e.g. after re-analysis).
     */
    public void invalidateCache() {
        cachedNodes = null;
        cachedEdges = null;
    }

    @GetMapping
    public Map<String, Object> getTopology() {
        ensureCacheLoaded();
        requireCache();
        return topologyService.getTopology(cachedNodes, cachedEdges);
    }

    @GetMapping("/services/{name}")
    public Map<String, Object> serviceDetail(@PathVariable String name) {
        ensureCacheLoaded();
        requireCache();
        return topologyService.serviceDetail(name, cachedNodes, cachedEdges);
    }

    @GetMapping("/services/{name}/deps")
    public Map<String, Object> serviceDependencies(@PathVariable String name) {
        ensureCacheLoaded();
        requireCache();
        return topologyService.serviceDependencies(name, cachedNodes, cachedEdges);
    }

    @GetMapping("/services/{name}/dependents")
    public Map<String, Object> serviceDependents(@PathVariable String name) {
        ensureCacheLoaded();
        requireCache();
        return topologyService.serviceDependents(name, cachedNodes, cachedEdges);
    }

    @GetMapping("/blast-radius/{nodeId}")
    public Map<String, Object> blastRadius(@PathVariable String nodeId) {
        ensureCacheLoaded();
        requireCache();
        return topologyService.blastRadius(nodeId, cachedNodes, cachedEdges);
    }

    @GetMapping("/path")
    public List<Map<String, Object>> findPath(
            @RequestParam("from") String source,
            @RequestParam("to") String target) {
        ensureCacheLoaded();
        requireCache();
        return topologyService.findPath(source, target, cachedNodes, cachedEdges);
    }

    @GetMapping("/bottlenecks")
    public List<Map<String, Object>> findBottlenecks() {
        ensureCacheLoaded();
        requireCache();
        return topologyService.findBottlenecks(cachedNodes, cachedEdges);
    }

    @GetMapping("/circular")
    public List<List<String>> findCircularDeps() {
        ensureCacheLoaded();
        requireCache();
        return topologyService.findCircularDeps(cachedNodes, cachedEdges);
    }

    @GetMapping("/dead")
    public List<Map<String, Object>> findDeadServices() {
        ensureCacheLoaded();
        requireCache();
        return topologyService.findDeadServices(cachedNodes, cachedEdges);
    }

    private void requireCache() {
        if (cachedNodes == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No analysis cache found. Run analyze first.");
        }
    }
}
