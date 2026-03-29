package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphCommandTest {

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream capture;

    @BeforeEach
    void setUp() {
        capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Test
    void jsonFormatOutputContainsNodes() {
        var store = mock(GraphStore.class);
        var node = createNode("test:id:1", "TestClass", NodeKind.CLASS);
        when(store.findAllPaginated(anyInt(), anyInt())).thenReturn(List.of(node));

        var cmd = new GraphCommand(store);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--format", "json");

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("\"nodes\""), "Should contain nodes array");
        assertTrue(output.contains("TestClass"), "Should contain node label");
        assertTrue(output.contains("class"), "Should contain node kind");
    }

    @Test
    void mermaidFormatOutputContainsGraph() {
        var store = mock(GraphStore.class);
        var node = createNode("test:id:1", "MyService", NodeKind.CLASS);
        when(store.findAllPaginated(anyInt(), anyInt())).thenReturn(List.of(node));

        var cmd = new GraphCommand(store);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--format", "mermaid");

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("graph TD"), "Should contain mermaid graph header");
        assertTrue(output.contains("MyService"), "Should contain node label");
    }

    @Test
    void dotFormatOutputContainsDigraph() {
        var store = mock(GraphStore.class);
        var node = createNode("test:id:1", "MyController", NodeKind.CLASS);
        when(store.findAllPaginated(anyInt(), anyInt())).thenReturn(List.of(node));

        var cmd = new GraphCommand(store);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--format", "dot");

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("digraph G"), "Should contain dot header");
        assertTrue(output.contains("MyController"), "Should contain node label");
    }

    @Test
    void emptyGraphReturnsWarning() {
        var store = mock(GraphStore.class);
        when(store.findAllPaginated(anyInt(), anyInt())).thenReturn(List.of());

        var cmd = new GraphCommand(store);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".");

        assertEquals(1, exitCode);
    }

    private CodeNode createNode(String id, String label, NodeKind kind) {
        var node = new CodeNode();
        node.setId(id);
        node.setLabel(label);
        node.setKind(kind);
        return node;
    }
}
