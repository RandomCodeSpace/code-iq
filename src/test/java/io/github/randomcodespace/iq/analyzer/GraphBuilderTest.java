package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GraphBuilderTest {

    @Test
    void addResultAccumulatesNodesAndEdges() {
        var builder = new GraphBuilder();
        var nodeA = new CodeNode("a", NodeKind.CLASS, "ClassA");
        var nodeB = new CodeNode("b", NodeKind.CLASS, "ClassB");

        var edge = new CodeEdge();
        edge.setId("e1");
        edge.setKind(EdgeKind.CALLS);
        edge.setSourceId("a");
        edge.setTarget(nodeB);

        builder.addResult(DetectorResult.of(List.of(nodeA, nodeB), List.of(edge)));

        assertEquals(2, builder.getNodeCount());
        assertEquals(1, builder.getEdgeCount());
    }

    @Test
    void flushSeparatesValidAndDeferredEdges() {
        var builder = new GraphBuilder();
        var nodeA = new CodeNode("a", NodeKind.CLASS, "ClassA");
        var nodeB = new CodeNode("b", NodeKind.CLASS, "ClassB");

        // Valid edge: both nodes exist
        var validEdge = new CodeEdge();
        validEdge.setId("e1");
        validEdge.setKind(EdgeKind.CALLS);
        validEdge.setSourceId("a");
        validEdge.setTarget(nodeB);

        // Deferred edge: target doesn't exist
        var missingTarget = new CodeNode("missing", NodeKind.CLASS, "Missing");
        var deferredEdge = new CodeEdge();
        deferredEdge.setId("e2");
        deferredEdge.setKind(EdgeKind.CALLS);
        deferredEdge.setSourceId("a");
        deferredEdge.setTarget(missingTarget);

        builder.addResult(DetectorResult.of(List.of(nodeA, nodeB), List.of(validEdge, deferredEdge)));

        GraphBuilder.FlushResult result = builder.flush();
        assertEquals(2, result.nodes().size());
        assertEquals(1, result.edges().size());
        assertEquals("e1", result.edges().getFirst().getId());
    }

    @Test
    void flushDeferredRecoversPreviouslyMissingEdges() {
        var builder = new GraphBuilder();
        var nodeA = new CodeNode("a", NodeKind.CLASS, "ClassA");
        var nodeC = new CodeNode("c", NodeKind.CLASS, "ClassC");

        // Edge referencing node not yet added
        var edge = new CodeEdge();
        edge.setId("e1");
        edge.setKind(EdgeKind.CALLS);
        edge.setSourceId("a");
        edge.setTarget(nodeC);

        // First batch: only nodeA
        builder.addNodes(List.of(nodeA));
        builder.addEdges(List.of(edge));
        builder.flush();

        // Now add the missing node
        builder.addNodes(List.of(nodeC));

        // flushDeferred should recover the edge
        List<CodeEdge> recovered = builder.flushDeferred();
        assertEquals(1, recovered.size());
        assertEquals("e1", recovered.getFirst().getId());
    }

    @Test
    void emptyBuilderFlushesCleanly() {
        var builder = new GraphBuilder();
        GraphBuilder.FlushResult result = builder.flush();

        assertTrue(result.nodes().isEmpty());
        assertTrue(result.edges().isEmpty());
        assertTrue(builder.flushDeferred().isEmpty());
    }

    @Test
    void multipleAddResultsMerge() {
        var builder = new GraphBuilder();

        var node1 = new CodeNode("a", NodeKind.CLASS, "A");
        var node2 = new CodeNode("b", NodeKind.METHOD, "B");
        builder.addResult(DetectorResult.of(List.of(node1), List.of()));
        builder.addResult(DetectorResult.of(List.of(node2), List.of()));

        assertEquals(2, builder.getNodeCount());
    }

    @Test
    void getNodesReturnsImmutableCopy() {
        var builder = new GraphBuilder();
        builder.addNodes(List.of(new CodeNode("a", NodeKind.CLASS, "A")));

        List<CodeNode> nodes = builder.getNodes();
        assertThrows(UnsupportedOperationException.class, () -> nodes.add(new CodeNode()));
    }

    @Test
    void runLinkersAddsLinkerResults() {
        var builder = new GraphBuilder();
        var node = new CodeNode("a", NodeKind.CLASS, "A");
        node.setModule("com.example");
        builder.addNodes(List.of(node));

        // Linker that adds a node
        builder.runLinkers(List.of((nodes, edges) -> {
            var moduleNode = new CodeNode("module:com.example", NodeKind.MODULE, "com.example");
            var edge = new CodeEdge();
            edge.setId("contains:1");
            edge.setKind(EdgeKind.CONTAINS);
            edge.setSourceId("module:com.example");
            edge.setTarget(node);
            return new io.github.randomcodespace.iq.analyzer.linker.LinkResult(List.of(moduleNode), List.of(edge));
        }));

        assertEquals(2, builder.getNodeCount()); // original + MODULE
        assertEquals(1, builder.getEdgeCount()); // CONTAINS edge
    }
}
