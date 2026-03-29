package io.github.randomcodespace.iq.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CodeNodeEdgeExtendedTest {

    // ==================== CodeNode ====================

    @Test
    void codeNodeDefaultConstructor() {
        var node = new CodeNode();
        assertNull(node.getId());
        assertNull(node.getKind());
        assertNull(node.getLabel());
        assertNotNull(node.getProperties());
        assertNotNull(node.getEdges());
        assertNotNull(node.getAnnotations());
    }

    @Test
    void codeNodeThreeArgConstructor() {
        var node = new CodeNode("id:1", NodeKind.CLASS, "MyClass");
        assertEquals("id:1", node.getId());
        assertEquals(NodeKind.CLASS, node.getKind());
        assertEquals("MyClass", node.getLabel());
    }

    @Test
    void codeNodeSettersAndGetters() {
        var node = new CodeNode();
        node.setId("test:id");
        node.setKind(NodeKind.ENDPOINT);
        node.setLabel("GET /api");
        node.setFqn("com.example.Controller::getApi");
        node.setModule("web");
        node.setFilePath("Controller.java");
        node.setLineStart(10);
        node.setLineEnd(20);
        node.setLayer("backend");
        node.setAnnotations(List.of("@RestController"));
        node.setProperties(Map.of("method", "GET"));
        node.setEdges(List.of());

        assertEquals("test:id", node.getId());
        assertEquals(NodeKind.ENDPOINT, node.getKind());
        assertEquals("GET /api", node.getLabel());
        assertEquals("com.example.Controller::getApi", node.getFqn());
        assertEquals("web", node.getModule());
        assertEquals("Controller.java", node.getFilePath());
        assertEquals(10, node.getLineStart());
        assertEquals(20, node.getLineEnd());
        assertEquals("backend", node.getLayer());
        assertEquals(List.of("@RestController"), node.getAnnotations());
        assertEquals("GET", node.getProperties().get("method"));
        assertTrue(node.getEdges().isEmpty());
    }

    @Test
    void codeNodeEqualsAndHashCode() {
        var node1 = new CodeNode("id:1", NodeKind.CLASS, "A");
        var node2 = new CodeNode("id:1", NodeKind.METHOD, "B");
        var node3 = new CodeNode("id:2", NodeKind.CLASS, "A");

        assertEquals(node1, node2, "Same ID should be equal");
        assertNotEquals(node1, node3, "Different ID should not be equal");
        assertEquals(node1.hashCode(), node2.hashCode());
        assertNotEquals(node1, null);
        assertNotEquals(node1, "not a node");
        assertEquals(node1, node1);
    }

    @Test
    void codeNodeToString() {
        var node = new CodeNode("id:1", NodeKind.CLASS, "MyClass");
        String str = node.toString();
        assertTrue(str.contains("id:1"));
        assertTrue(str.contains("MyClass"));
    }

    // ==================== CodeEdge ====================

    @Test
    void codeEdgeDefaultConstructor() {
        var edge = new CodeEdge();
        assertNull(edge.getId());
        assertNull(edge.getKind());
        assertNull(edge.getSourceId());
        assertNull(edge.getTarget());
        assertNotNull(edge.getProperties());
    }

    @Test
    void codeEdgeFourArgConstructor() {
        var target = new CodeNode("n2", NodeKind.CLASS, "B");
        var edge = new CodeEdge("e:1", EdgeKind.CALLS, "n1", target);
        assertEquals("e:1", edge.getId());
        assertEquals(EdgeKind.CALLS, edge.getKind());
        assertEquals("n1", edge.getSourceId());
        assertEquals(target, edge.getTarget());
    }

    @Test
    void codeEdgeSettersAndGetters() {
        var edge = new CodeEdge();
        var target = new CodeNode("n2", NodeKind.CLASS, "B");
        edge.setId("e:1");
        edge.setKind(EdgeKind.DEPENDS_ON);
        edge.setSourceId("n1");
        edge.setTarget(target);
        edge.setProperties(Map.of("weight", 1));

        assertEquals("e:1", edge.getId());
        assertEquals(EdgeKind.DEPENDS_ON, edge.getKind());
        assertEquals("n1", edge.getSourceId());
        assertEquals(target, edge.getTarget());
        assertEquals(1, edge.getProperties().get("weight"));
        assertNull(edge.getInternalId());
    }

    @Test
    void codeEdgeEqualsAndHashCode() {
        var edge1 = new CodeEdge("e:1", EdgeKind.CALLS, "n1", null);
        var edge2 = new CodeEdge("e:1", EdgeKind.DEPENDS_ON, "n2", null);
        var edge3 = new CodeEdge("e:2", EdgeKind.CALLS, "n1", null);

        assertEquals(edge1, edge2, "Same ID should be equal");
        assertNotEquals(edge1, edge3, "Different ID should not be equal");
        assertEquals(edge1.hashCode(), edge2.hashCode());
        assertNotEquals(edge1, null);
        assertNotEquals(edge1, "not an edge");
        assertEquals(edge1, edge1);
    }

    @Test
    void codeEdgeToString() {
        var edge = new CodeEdge("e:1", EdgeKind.CALLS, "n1", null);
        String str = edge.toString();
        assertTrue(str.contains("e:1"));
        assertTrue(str.contains("CALLS"));
    }
}
