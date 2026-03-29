package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.analyzer.linker.LinkResult;
import io.github.randomcodespace.iq.analyzer.linker.Linker;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Buffers nodes and edges from detector results, then flushes them
 * in the correct order (nodes first, then edges) to ensure edges
 * never reference non-existent nodes.
 * <p>
 * Deferred edges whose target node doesn't exist after the first
 * flush are retried after all batches complete.
 * <p>
 * Thread safety: callers must synchronize externally when adding
 * results from multiple threads (the Analyzer uses indexed result
 * slots to avoid contention).
 */
public class GraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(GraphBuilder.class);

    private final List<CodeNode> allNodes = new ArrayList<>();
    private final List<CodeEdge> allEdges = new ArrayList<>();
    private final List<CodeEdge> deferredEdges = new ArrayList<>();
    private final int batchSize;

    public GraphBuilder() {
        this(500);
    }

    public GraphBuilder(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
    }

    /**
     * Add all nodes and edges from a detector result.
     */
    public void addResult(DetectorResult result) {
        allNodes.addAll(result.nodes());
        allEdges.addAll(result.edges());
    }

    /**
     * Add nodes directly (used by linkers).
     */
    public void addNodes(List<CodeNode> nodes) {
        allNodes.addAll(nodes);
    }

    /**
     * Add edges directly (used by linkers).
     */
    public void addEdges(List<CodeEdge> edges) {
        allEdges.addAll(edges);
    }

    /**
     * Flush all buffered data: insert nodes first, then edges.
     * Edges whose source or target node doesn't exist are deferred.
     *
     * @return a snapshot of all valid nodes and edges
     */
    public FlushResult flush() {
        // Build the set of all node IDs
        Set<String> nodeIds = new HashSet<>();
        for (CodeNode node : allNodes) {
            nodeIds.add(node.getId());
        }

        // Partition edges: valid vs deferred
        List<CodeEdge> validEdges = new ArrayList<>();
        deferredEdges.clear();
        for (CodeEdge edge : allEdges) {
            String sourceId = edge.getSourceId();
            String targetId = edge.getTarget() != null ? edge.getTarget().getId() : null;
            if (sourceId != null && nodeIds.contains(sourceId)
                    && targetId != null && nodeIds.contains(targetId)) {
                validEdges.add(edge);
            } else {
                deferredEdges.add(edge);
            }
        }

        if (!deferredEdges.isEmpty()) {
            log.debug("Deferred {} edges with missing source/target nodes", deferredEdges.size());
        }

        return new FlushResult(List.copyOf(allNodes), List.copyOf(validEdges));
    }

    /**
     * Retry deferred edges after all batches have been processed.
     * Returns edges that now have valid source and target nodes.
     */
    public List<CodeEdge> flushDeferred() {
        if (deferredEdges.isEmpty()) return List.of();

        Set<String> nodeIds = new HashSet<>();
        for (CodeNode node : allNodes) {
            nodeIds.add(node.getId());
        }

        List<CodeEdge> recovered = new ArrayList<>();
        for (CodeEdge edge : deferredEdges) {
            String sourceId = edge.getSourceId();
            String targetId = edge.getTarget() != null ? edge.getTarget().getId() : null;
            if (sourceId != null && nodeIds.contains(sourceId)
                    && targetId != null && nodeIds.contains(targetId)) {
                recovered.add(edge);
            }
        }

        if (!recovered.isEmpty()) {
            log.debug("Recovered {} deferred edges", recovered.size());
        }
        int dropped = deferredEdges.size() - recovered.size();
        if (dropped > 0) {
            log.debug("Dropped {} edges with permanently missing nodes", dropped);
        }
        deferredEdges.clear();
        return recovered;
    }

    /**
     * Run linkers against the current graph state, adding their results.
     */
    public void runLinkers(List<Linker> linkers) {
        for (Linker linker : linkers) {
            try {
                LinkResult result = linker.link(
                        List.copyOf(allNodes),
                        List.copyOf(allEdges)
                );
                if (!result.nodes().isEmpty()) {
                    allNodes.addAll(result.nodes());
                }
                if (!result.edges().isEmpty()) {
                    allEdges.addAll(result.edges());
                }
            } catch (Exception e) {
                log.warn("Linker {} failed", linker.getClass().getSimpleName(), e);
            }
        }
    }

    /**
     * Return all accumulated nodes (read-only snapshot).
     */
    public List<CodeNode> getNodes() {
        return List.copyOf(allNodes);
    }

    /**
     * Return all accumulated edges (read-only snapshot).
     */
    public List<CodeEdge> getEdges() {
        return List.copyOf(allEdges);
    }

    public int getNodeCount() {
        return allNodes.size();
    }

    public int getEdgeCount() {
        return allEdges.size();
    }

    /**
     * Result of a flush operation — all valid nodes and edges.
     */
    public record FlushResult(List<CodeNode> nodes, List<CodeEdge> edges) {}
}
