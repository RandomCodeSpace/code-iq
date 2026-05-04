package io.github.randomcodespace.iq.api;

import io.github.randomcodespace.iq.query.TopologyService;
import io.github.randomcodespace.iq.query.TopologySnapshotProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * REST API controller for service topology queries.
 *
 * <p>Reads topology data from {@link TopologySnapshotProvider} which is
 * shared with {@code McpTools}. Previously this controller kept its own
 * lifetime-of-process {@code AtomicReference<List<CodeNode>>}, doubling the
 * topology heap footprint under mixed REST + MCP traffic.
 */
@RestController
@RequestMapping("/api/topology")
@Profile("serving")
public class TopologyController {

    private final TopologyService topologyService;
    private final TopologySnapshotProvider snapshotProvider;

    public TopologyController(TopologyService topologyService,
                              TopologySnapshotProvider snapshotProvider) {
        this.topologyService = topologyService;
        this.snapshotProvider = snapshotProvider;
    }

    private TopologySnapshotProvider.Snapshot requireSnapshot() {
        TopologySnapshotProvider.Snapshot snap = snapshotProvider.snapshot();
        // 404 only when no source was reachable (preserves legacy behaviour
        // where ensureDataLoaded() left cachedNodes==null vs []). A loaded
        // but empty graph still returns 200 with empty topology.
        if (!snap.loaded()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No analysis cache found. Run analyze first.");
        }
        return snap;
    }

    @GetMapping
    public Map<String, Object> getTopology() {
        TopologySnapshotProvider.Snapshot s = requireSnapshot();
        return topologyService.getTopology(s.nodes(), s.edges());
    }

    @GetMapping("/services/{name}")
    public Map<String, Object> serviceDetail(@PathVariable String name) {
        TopologySnapshotProvider.Snapshot s = requireSnapshot();
        return topologyService.serviceDetail(name, s.nodes(), s.edges());
    }

    @GetMapping("/services/{name}/deps")
    public Map<String, Object> serviceDependencies(@PathVariable String name) {
        TopologySnapshotProvider.Snapshot s = requireSnapshot();
        return topologyService.serviceDependencies(name, s.nodes(), s.edges());
    }

    @GetMapping("/services/{name}/dependents")
    public Map<String, Object> serviceDependents(@PathVariable String name) {
        TopologySnapshotProvider.Snapshot s = requireSnapshot();
        return topologyService.serviceDependents(name, s.nodes(), s.edges());
    }

    @GetMapping("/blast-radius/{nodeId}")
    public Map<String, Object> blastRadius(@PathVariable String nodeId) {
        TopologySnapshotProvider.Snapshot s = requireSnapshot();
        return topologyService.blastRadius(nodeId, s.nodes(), s.edges());
    }

    @GetMapping("/path")
    public List<Map<String, Object>> findPath(
            @RequestParam("from") String source,
            @RequestParam("to") String target) {
        TopologySnapshotProvider.Snapshot s = requireSnapshot();
        return topologyService.findPath(source, target, s.nodes(), s.edges());
    }

    @GetMapping("/bottlenecks")
    public List<Map<String, Object>> findBottlenecks() {
        TopologySnapshotProvider.Snapshot s = requireSnapshot();
        return topologyService.findBottlenecks(s.nodes(), s.edges());
    }

    @GetMapping("/circular")
    public List<List<String>> findCircularDeps() {
        TopologySnapshotProvider.Snapshot s = requireSnapshot();
        return topologyService.findCircularDeps(s.nodes(), s.edges());
    }

    @GetMapping("/dead")
    public List<Map<String, Object>> findDeadServices() {
        TopologySnapshotProvider.Snapshot s = requireSnapshot();
        return topologyService.findDeadServices(s.nodes(), s.edges());
    }
}
