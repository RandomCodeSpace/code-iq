package io.github.randomcodespace.iq.analyzer.linker;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleContainmentLinkerTest {

    private final ModuleContainmentLinker linker = new ModuleContainmentLinker();

    @Test
    void createsModuleNodeAndContainsEdge() {
        var classNode = new CodeNode("cls:com.example.UserService", NodeKind.CLASS, "UserService");
        classNode.setModule("com.example");

        LinkResult result = linker.link(List.of(classNode), List.of());

        // Should create 1 module node and 1 CONTAINS edge
        assertEquals(1, result.nodes().size());
        assertEquals("module:com.example", result.nodes().getFirst().getId());
        assertEquals(NodeKind.MODULE, result.nodes().getFirst().getKind());
        assertEquals("com.example", result.nodes().getFirst().getLabel());

        assertEquals(1, result.edges().size());
        CodeEdge edge = result.edges().getFirst();
        assertEquals(EdgeKind.CONTAINS, edge.getKind());
        assertEquals("module:com.example", edge.getSourceId());
        assertEquals("cls:com.example.UserService", edge.getTarget().getId());
    }

    @Test
    void groupsMultipleNodesUnderSameModule() {
        var node1 = new CodeNode("cls:A", NodeKind.CLASS, "A");
        node1.setModule("com.example");
        var node2 = new CodeNode("cls:B", NodeKind.CLASS, "B");
        node2.setModule("com.example");

        LinkResult result = linker.link(List.of(node1, node2), List.of());

        assertEquals(1, result.nodes().size()); // One MODULE node
        assertEquals(2, result.edges().size()); // Two CONTAINS edges
    }

    @Test
    void createsMultipleModuleNodes() {
        var node1 = new CodeNode("cls:A", NodeKind.CLASS, "A");
        node1.setModule("com.alpha");
        var node2 = new CodeNode("cls:B", NodeKind.CLASS, "B");
        node2.setModule("com.beta");

        LinkResult result = linker.link(List.of(node1, node2), List.of());

        assertEquals(2, result.nodes().size());
        assertEquals(2, result.edges().size());
    }

    @Test
    void skipsNodesWithoutModule() {
        var node = new CodeNode("cls:A", NodeKind.CLASS, "A");
        // No module set

        LinkResult result = linker.link(List.of(node), List.of());

        assertTrue(result.nodes().isEmpty());
        assertTrue(result.edges().isEmpty());
    }

    @Test
    void skipsExistingModuleNodes() {
        var existingModule = new CodeNode("module:com.example", NodeKind.MODULE, "com.example");
        var classNode = new CodeNode("cls:A", NodeKind.CLASS, "A");
        classNode.setModule("com.example");

        LinkResult result = linker.link(List.of(existingModule, classNode), List.of());

        // Should NOT create a new module node (already exists)
        assertTrue(result.nodes().isEmpty());
        // Should still create the CONTAINS edge
        assertEquals(1, result.edges().size());
    }

    @Test
    void avoidsDuplicateContainsEdges() {
        var classNode = new CodeNode("cls:A", NodeKind.CLASS, "A");
        classNode.setModule("com.example");

        // Pre-existing CONTAINS edge
        var existing = new CodeEdge();
        existing.setId("existing");
        existing.setKind(EdgeKind.CONTAINS);
        existing.setSourceId("module:com.example");
        existing.setTarget(classNode);

        LinkResult result = linker.link(List.of(classNode), List.of(existing));

        // Module node should be created (wasn't in nodes list)
        assertEquals(1, result.nodes().size());
        // But edge should be skipped (already exists)
        assertTrue(result.edges().isEmpty());
    }

    @Test
    void emptyModuleStringIsSkipped() {
        var node = new CodeNode("cls:A", NodeKind.CLASS, "A");
        node.setModule("");

        LinkResult result = linker.link(List.of(node), List.of());

        assertTrue(result.nodes().isEmpty());
        assertTrue(result.edges().isEmpty());
    }
}
