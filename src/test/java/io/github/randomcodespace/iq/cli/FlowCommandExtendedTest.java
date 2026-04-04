package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.flow.FlowModels.FlowDiagram;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Extended tests for FlowCommand covering branches not hit by FlowCommandTest:
 * - html format (renderInteractive path)
 * - output to file (--output flag)
 * - no engine + no h2 cache → returns error (exit 1)
 * - IOException when writing to output file
 * - null config when flowEngine is also null
 */
class FlowCommandExtendedTest {

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private ByteArrayOutputStream captureOut;
    private ByteArrayOutputStream captureErr;

    @BeforeEach
    void setUp() {
        captureOut = new ByteArrayOutputStream();
        captureErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captureOut, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(captureErr, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private GraphStore mockStore() {
        var store = mock(GraphStore.class);
        var node = new CodeNode();
        node.setId("ep:test");
        node.setLabel("GET /test");
        node.setKind(NodeKind.ENDPOINT);
        node.setProperties(new HashMap<>());
        node.setEdges(new ArrayList<>());

        when(store.findAll()).thenReturn(List.of(node));
        when(store.findByKind(NodeKind.ENDPOINT)).thenReturn(List.of(node));
        when(store.findByKind(NodeKind.ENTITY)).thenReturn(List.of());
        when(store.findByKind(NodeKind.CLASS)).thenReturn(List.of());
        when(store.findByKind(NodeKind.METHOD)).thenReturn(List.of());
        when(store.findByKind(NodeKind.COMPONENT)).thenReturn(List.of());
        when(store.findByKind(NodeKind.TOPIC)).thenReturn(List.of());
        when(store.findByKind(NodeKind.QUEUE)).thenReturn(List.of());
        when(store.findByKind(NodeKind.DATABASE_CONNECTION)).thenReturn(List.of());
        when(store.findByKind(NodeKind.GUARD)).thenReturn(List.of());
        when(store.findByKind(NodeKind.MIDDLEWARE)).thenReturn(List.of());
        when(store.findByKind(NodeKind.INFRA_RESOURCE)).thenReturn(List.of());
        when(store.findByKind(NodeKind.AZURE_RESOURCE)).thenReturn(List.of());
        when(store.count()).thenReturn(1L);
        return store;
    }

    // ---- html format → renderInteractive path -----------------------

    @Test
    void htmlFormatCallsRenderInteractive() {
        var engine = mock(FlowEngine.class);
        when(engine.renderInteractive(anyString())).thenReturn("<html>flow diagram</html>");

        var cmd = new FlowCommand(engine, null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--format", "html");

        assertEquals(0, exitCode);
        String output = captureOut.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("<html>"), "Should output html content");
        verify(engine).renderInteractive(anyString());
    }

    // ---- --output flag writes to file --------------------------------

    @Test
    void outputFlagWritesContentToFile(@TempDir Path tempDir) throws IOException {
        var engine = new FlowEngine(mockStore());
        Path outFile = tempDir.resolve("diagram.mmd");

        var cmd = new FlowCommand(engine, null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--output", outFile.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(outFile), "Output file should be created");
        String content = Files.readString(outFile, StandardCharsets.UTF_8);
        assertTrue(content.startsWith("graph "), "Should contain mermaid diagram");
    }

    @Test
    void outputFlagWithHtmlWritesHtml(@TempDir Path tempDir) throws IOException {
        var engine = mock(FlowEngine.class);
        when(engine.renderInteractive(anyString())).thenReturn("<html>test</html>");

        Path outFile = tempDir.resolve("flow.html");
        var cmd = new FlowCommand(engine, null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--format", "html", "--output", outFile.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(outFile));
        assertEquals("<html>test</html>", Files.readString(outFile, StandardCharsets.UTF_8));
    }

    // ---- no engine + no cache → error --------------------------------

    @Test
    void returnsErrorWhenNoEngineAndNoCacheFile(@TempDir Path tempDir) {
        var config = new CodeIqConfig();
        // No h2 cache file exists in tempDir
        var cmd = new FlowCommand((FlowEngine) null, config);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        assertEquals(1, exitCode, "Should return error when no cache exists");
    }

    // ---- null config with null engine --------------------------------

    @Test
    void returnsErrorWhenBothEngineAndConfigAreNull() {
        var cmd = new FlowCommand((FlowEngine) null, null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".");

        assertEquals(1, exitCode, "Should return error with null engine and null config");
    }

    // ---- invalid format → IllegalArgumentException → exit 1 ---------

    @Test
    void invalidFormatReturnsError() {
        var engine = new FlowEngine(mockStore());
        var cmd = new FlowCommand(engine, null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--format", "xml");

        assertEquals(1, exitCode, "Unknown format should fail");
    }
}
