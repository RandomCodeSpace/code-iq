package io.github.randomcodespace.iq.analyzer.linker;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;

import java.util.List;

/**
 * Cross-file relationship inferencer.
 * Linkers run after all detectors and examine the full graph to create
 * additional edges (and sometimes nodes) that span file boundaries.
 */
public interface Linker {

    /**
     * Examine the current graph state and return inferred nodes and edges.
     *
     * @param nodes all nodes currently in the graph
     * @param edges all edges currently in the graph
     * @return new nodes and edges to add
     */
    LinkResult link(List<CodeNode> nodes, List<CodeEdge> edges);
}
