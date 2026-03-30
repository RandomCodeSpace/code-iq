package io.github.randomcodespace.iq.flow;

import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;

import java.util.List;

/**
 * Abstraction over graph data access for flow diagram generation.
 * Allows FlowEngine to work from either Neo4j (GraphStore) or H2 cache.
 */
public interface FlowDataSource {

    List<CodeNode> findAll();

    List<CodeNode> findByKind(NodeKind kind);

    long count();
}
