package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FlowCommandTest {

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
    void overviewMermaidFormatWorks() {
        var store = mock(GraphStore.class);
        var node = createNode("test:1", "MyService", NodeKind.CLASS, "backend");
        when(store.findAllPaginated(anyInt(), anyInt())).thenReturn(List.of(node));

        var cmd = new FlowCommand(store);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".");

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("graph TD"), "Should contain mermaid header");
        assertTrue(output.contains("backend"), "Should contain layer");
    }

    @Test
    void jsonFormatWorks() {
        var store = mock(GraphStore.class);
        var node = createNode("test:1", "MyService", NodeKind.CLASS, "backend");
        when(store.findAllPaginated(anyInt(), anyInt())).thenReturn(List.of(node));

        var cmd = new FlowCommand(store);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--format", "json");

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("\"view\""), "Should contain view key");
        assertTrue(output.contains("\"total_nodes\""), "Should contain total_nodes");
    }

    @Test
    void emptyGraphReturnsWarning() {
        var store = mock(GraphStore.class);
        when(store.findAllPaginated(anyInt(), anyInt())).thenReturn(List.of());

        var cmd = new FlowCommand(store);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".");

        assertEquals(1, exitCode);
    }

    private CodeNode createNode(String id, String label, NodeKind kind, String layer) {
        var node = new CodeNode();
        node.setId(id);
        node.setLabel(label);
        node.setKind(kind);
        node.setLayer(layer);
        return node;
    }
}
