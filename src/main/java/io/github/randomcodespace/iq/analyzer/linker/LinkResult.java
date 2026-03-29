package io.github.randomcodespace.iq.analyzer.linker;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;

import java.util.List;

/**
 * Result returned by a Linker: new nodes and edges to add to the graph.
 *
 * @param nodes newly created nodes (e.g. MODULE nodes)
 * @param edges newly inferred edges
 */
public record LinkResult(List<CodeNode> nodes, List<CodeEdge> edges) {

    public static LinkResult empty() {
        return new LinkResult(List.of(), List.of());
    }

    public static LinkResult ofEdges(List<CodeEdge> edges) {
        return new LinkResult(List.of(), edges);
    }
}
