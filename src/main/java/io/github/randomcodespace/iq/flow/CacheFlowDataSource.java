package io.github.randomcodespace.iq.flow;

import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;

import java.util.List;

/**
 * FlowDataSource backed by a pre-loaded list of nodes (from H2 cache).
 */
public class CacheFlowDataSource implements FlowDataSource {

    private final List<CodeNode> nodes;

    public CacheFlowDataSource(List<CodeNode> nodes) {
        this.nodes = nodes;
    }

    @Override
    public List<CodeNode> findAll() {
        return nodes;
    }

    @Override
    public List<CodeNode> findByKind(NodeKind kind) {
        return nodes.stream()
                .filter(n -> n.getKind() == kind)
                .toList();
    }

    @Override
    public long count() {
        return nodes.size();
    }
}
