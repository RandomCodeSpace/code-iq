package io.github.randomcodespace.iq.query;

import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single owner of the in-heap topology snapshot used by both the REST topology
 * endpoints ({@code TopologyController}) and the topology MCP tools
 * ({@code McpTools}).
 *
 * <p>Previously each consumer kept its own
 * {@code AtomicReference<List<CodeNode>>} — {@code TopologyController} never
 * expired its copy (lifetime-of-process), {@code McpTools} had a 60 s TTL.
 * Under mixed REST + MCP traffic two multi-MB snapshots coexisted on heap.
 * This bean unifies both into one 60-second TTL snapshot, halving the
 * topology heap ceiling.
 *
 * <p><b>Why a TTL on read-only data?</b> The window doesn't refresh stale data
 * (it doesn't change at serve); it deduplicates concurrent loads — if two
 * requests hit the cold path at once, only one full {@code findAll()} pays
 * the cost. After 60 s of idle the snapshot becomes GC-eligible, so an idle
 * pod releases the heap.
 *
 * <p><b>Neo4j first, H2 fallback.</b> Preserves the legacy
 * {@code TopologyController.ensureDataLoaded()} behaviour where serve could
 * boot off a raw H2 cache (pre-enrich). Real serve deploys go through enrich.
 *
 * <p><b>Empty data is not an error here.</b> Returning an empty
 * {@link Snapshot} lets each consumer choose its own error shape:
 * {@code RuntimeException} for MCP (becomes a structured error envelope),
 * {@code ResponseStatusException(NOT_FOUND)} for REST.
 */
@Component
@Profile("serving")
public class TopologySnapshotProvider {

    /**
     * Topology snapshot. {@code loaded} distinguishes "source was reachable
     * and returned data (possibly empty)" from "no source available at all"
     * — the former is a legitimate 200-with-empty-body response, the latter
     * is a 404 (run analyze/enrich first). Without this flag the controller
     * conflates "empty graph" with "never analyzed".
     */
    public record Snapshot(List<CodeNode> nodes, List<CodeEdge> edges, boolean loaded) {
        public static Snapshot of(List<CodeNode> nodes, List<CodeEdge> edges) {
            return new Snapshot(nodes, edges, true);
        }
        public static Snapshot unavailable() {
            return new Snapshot(List.of(), List.of(), false);
        }
    }

    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final GraphStore graphStore;
    private final CodeIqConfig config;
    private final AtomicReference<TimedSnapshot> cache = new AtomicReference<>();

    private record TimedSnapshot(Snapshot snapshot, long takenAtNanos) {}

    public TopologySnapshotProvider(@Autowired(required = false) GraphStore graphStore,
                                    CodeIqConfig config) {
        this.graphStore = graphStore;
        this.config = config;
    }

    public Snapshot snapshot() {
        long now = System.nanoTime();
        TimedSnapshot current = cache.get();
        if (current != null && (now - current.takenAtNanos()) < TTL_NANOS) {
            return current.snapshot();
        }
        Snapshot fresh = load();
        cache.set(new TimedSnapshot(fresh, System.nanoTime()));
        return fresh;
    }

    public void invalidate() {
        cache.set(null);
    }

    private Snapshot load() {
        if (graphStore != null) {
            try {
                if (graphStore.count() > 0) {
                    List<CodeNode> nodes = graphStore.findAll();
                    List<CodeEdge> edges = nodes.stream()
                            .flatMap(n -> n.getEdges().stream())
                            .toList();
                    return Snapshot.of(nodes, edges);
                }
            } catch (Exception ignored) {
                // Fall through to H2 fallback.
            }
        }

        Path root = Path.of(config.getRootPath()).toAbsolutePath().normalize();
        Path cachePath = root.resolve(config.getCacheDir()).resolve("analysis-cache.db");
        Path h2File = root.resolve(config.getCacheDir()).resolve("analysis-cache.mv.db");
        if (Files.exists(h2File)) {
            try (AnalysisCache h2 = new AnalysisCache(cachePath)) {
                return Snapshot.of(h2.loadAllNodes(), h2.loadAllEdges());
            }
        }
        return Snapshot.unavailable();
    }
}
