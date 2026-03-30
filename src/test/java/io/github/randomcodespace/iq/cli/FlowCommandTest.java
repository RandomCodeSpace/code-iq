package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        var store = mockStoreWithEndpoint();
        var engine = new FlowEngine(store);

        var cmd = new FlowCommand(engine, null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".");

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("graph "), "Should contain mermaid header");
    }

    @Test
    void jsonFormatWorks() {
        var store = mockStoreWithEndpoint();
        var engine = new FlowEngine(store);

        var cmd = new FlowCommand(engine, null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--format", "json");

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("\"view\""), "Should contain view key");
    }

    @Test
    void ciViewWorks() {
        var store = mockStoreWithEndpoint();
        var engine = new FlowEngine(store);

        var cmd = new FlowCommand(engine, null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--view", "ci");

        assertEquals(0, exitCode);
    }

    @Test
    void deployViewWorks() {
        var store = mockStoreWithEndpoint();
        var engine = new FlowEngine(store);

        var cmd = new FlowCommand(engine, null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--view", "deploy");

        assertEquals(0, exitCode);
    }

    @Test
    void runtimeViewWorks() {
        var store = mockStoreWithEndpoint();
        var engine = new FlowEngine(store);

        var cmd = new FlowCommand(engine, null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--view", "runtime");

        assertEquals(0, exitCode);
    }

    @Test
    void authViewWorks() {
        var store = mockStoreWithEndpoint();
        var engine = new FlowEngine(store);

        var cmd = new FlowCommand(engine, null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--view", "auth");

        assertEquals(0, exitCode);
    }

    @Test
    void invalidViewReturnsError() {
        var store = mockStoreWithEndpoint();
        var engine = new FlowEngine(store);

        var cmd = new FlowCommand(engine, null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--view", "nonexistent");

        assertEquals(1, exitCode);
    }

    private GraphStore mockStoreWithEndpoint() {
        var store = mock(GraphStore.class);
        var endpoint = new CodeNode();
        endpoint.setId("ep:test:endpoint:getUser");
        endpoint.setLabel("GET /users");
        endpoint.setKind(NodeKind.ENDPOINT);
        endpoint.setProperties(new HashMap<>());
        endpoint.setEdges(new ArrayList<>());

        when(store.findAll()).thenReturn(List.of(endpoint));
        when(store.findByKind(NodeKind.ENDPOINT)).thenReturn(List.of(endpoint));
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
}
