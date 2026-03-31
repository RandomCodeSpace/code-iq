package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.query.TopologyService;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final GraphStore graphStore;
    private final CodeIqConfig config;
    private volatile List<CodeNode> cachedNodes;
    private volatile List<CodeEdge> cachedEdges;

    public TopologyController(TopologyService topologyService,
                              @Autowired(required = false) GraphStore graphStore,
                              CodeIqConfig config) {
        this.topologyService = topologyService;
        this.graphStore = graphStore;
        this.config = config;
    }

    // --- Data loading: Neo4j first, H2 fallback ---

    /**
     * Check whether Neo4j has data available.
     */
    private volatile Boolean neo4jHasData;

    private boolean hasNeo4jData() {
        if (graphStore == null) return false;
        if (neo4jHasData != null) return neo4jHasData;
        try {
            neo4jHasData = graphStore.count() > 0;
        } catch (Exception e) {
            neo4jHasData = false;
        }
        return neo4jHasData;
    }

    /**
     * Load data from Neo4j if available, otherwise from H2 cache.
     */
    private synchronized void ensureDataLoaded() {
        if (cachedNodes != null) return;

        // Try Neo4j first (has enriched data with SERVICE nodes)
        if (hasNeo4jData()) {
            cachedNodes = graphStore.findAll();
            // Collect edges from all nodes' relationship lists
            cachedEdges = cachedNodes.stream()
                    .flatMap(n -> n.getEdges().stream())
                    .toList();
            return;
        }

        // Fall back to H2 cache
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
    public synchronized void invalidateCache() {
        cachedNodes = null;
        cachedEdges = null;
        neo4jHasData = null;
    }

    @GetMapping
    public Map<String, Object> getTopology() {
        ensureDataLoaded();
        requireCache();
        return topologyService.getTopology(cachedNodes, cachedEdges);
    }

    @GetMapping("/services/{name}")
    public Map<String, Object> serviceDetail(@PathVariable String name) {
        ensureDataLoaded();
        requireCache();
        return topologyService.serviceDetail(name, cachedNodes, cachedEdges);
    }

    @GetMapping("/services/{name}/deps")
    public Map<String, Object> serviceDependencies(@PathVariable String name) {
        ensureDataLoaded();
        requireCache();
        return topologyService.serviceDependencies(name, cachedNodes, cachedEdges);
    }

    @GetMapping("/services/{name}/dependents")
    public Map<String, Object> serviceDependents(@PathVariable String name) {
        ensureDataLoaded();
        requireCache();
        return topologyService.serviceDependents(name, cachedNodes, cachedEdges);
    }

    @GetMapping("/blast-radius/{nodeId}")
    public Map<String, Object> blastRadius(@PathVariable String nodeId) {
        ensureDataLoaded();
        requireCache();
        return topologyService.blastRadius(nodeId, cachedNodes, cachedEdges);
    }

    @GetMapping("/path")
    public List<Map<String, Object>> findPath(
            @RequestParam("from") String source,
            @RequestParam("to") String target) {
        ensureDataLoaded();
        requireCache();
        return topologyService.findPath(source, target, cachedNodes, cachedEdges);
    }

    @GetMapping("/bottlenecks")
    public List<Map<String, Object>> findBottlenecks() {
        ensureDataLoaded();
        requireCache();
        return topologyService.findBottlenecks(cachedNodes, cachedEdges);
    }

    @GetMapping("/circular")
    public List<List<String>> findCircularDeps() {
        ensureDataLoaded();
        requireCache();
        return topologyService.findCircularDeps(cachedNodes, cachedEdges);
    }

    @GetMapping("/dead")
    public List<Map<String, Object>> findDeadServices() {
        ensureDataLoaded();
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
