package io.github.randomcodespace.iq.detector.python;

import io.github.randomcodespace.iq.analyzer.InfraEndpoint;
import io.github.randomcodespace.iq.analyzer.InfrastructureRegistry;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;

import java.util.List;

/**
 * Abstract base for Python ORM detectors that emit DATABASE_CONNECTION nodes and CONNECTS_TO edges.
 * Extends {@link AbstractPythonAntlrDetector} with shared database node/edge helpers
 * used by Django and SQLAlchemy detectors.
 */
public abstract class AbstractPythonDbDetector extends AbstractPythonAntlrDetector {

    /**
     * Ensure a DATABASE_CONNECTION node exists in the result, creating it if needed.
     * Uses the first database from the InfrastructureRegistry if available,
     * otherwise creates a generic "database:unknown" node.
     *
     * @param registry infrastructure registry (may be null)
     * @param nodes    the nodes list to add the DB node to if missing
     * @return the database node ID
     */
    protected static String ensureDbNode(InfrastructureRegistry registry, List<CodeNode> nodes) {
        String dbNodeId;
        if (registry != null && !registry.getDatabases().isEmpty()) {
            InfraEndpoint db = registry.getDatabases().values().iterator().next();
            dbNodeId = "infra:" + db.id();
            if (nodes.stream().noneMatch(n -> dbNodeId.equals(n.getId()))) {
                CodeNode dbNode = new CodeNode(dbNodeId, NodeKind.DATABASE_CONNECTION,
                        db.name() + " (" + db.type() + ")");
                dbNode.getProperties().put("type", db.type());
                if (db.connectionUrl() != null) dbNode.getProperties().put("url", db.connectionUrl());
                nodes.add(dbNode);
            }
        } else {
            dbNodeId = "database:unknown";
            if (nodes.stream().noneMatch(n -> dbNodeId.equals(n.getId()))) {
                nodes.add(new CodeNode(dbNodeId, NodeKind.DATABASE_CONNECTION, "Database"));
            }
        }
        return dbNodeId;
    }

    /**
     * Add a CONNECTS_TO edge from the given source node to the database node.
     *
     * @param sourceId the source node ID
     * @param registry infrastructure registry (may be null)
     * @param nodes    the nodes list (used to find/create the DB node)
     * @param edges    the edges list to add the edge to
     */
    protected static void addDbEdge(String sourceId, InfrastructureRegistry registry,
            List<CodeNode> nodes, List<CodeEdge> edges) {
        String dbNodeId = ensureDbNode(registry, nodes);
        CodeNode targetRef = nodes.stream()
                .filter(n -> dbNodeId.equals(n.getId()))
                .findFirst()
                .orElseGet(() -> new CodeNode(dbNodeId, NodeKind.DATABASE_CONNECTION, "Database"));
        CodeEdge edge = new CodeEdge();
        edge.setId(sourceId + "->connects_to->" + dbNodeId);
        edge.setKind(EdgeKind.CONNECTS_TO);
        edge.setSourceId(sourceId);
        edge.setTarget(targetRef);
        edges.add(edge);
    }
}
