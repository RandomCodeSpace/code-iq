package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.query.QueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryCommandTest {

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
    void consumersOfShowsResults() {
        var service = mock(QueryService.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", 1);
        result.put("consumers", List.of(
                Map.of("id", "test:1", "kind", "class", "label", "ConsumerClass")
        ));
        when(service.consumersOf("my-target")).thenReturn(result);

        var cmd = new QueryCommand(service);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--consumers-of", "my-target");

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("Consumers of my-target"), "Should show title");
        assertTrue(output.contains("ConsumerClass"), "Should show consumer");
    }

    @Test
    void noOptionShowsWarning() {
        var service = mock(QueryService.class);
        var cmd = new QueryCommand(service);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".");

        assertEquals(1, exitCode);
    }

    @Test
    void shortestPathShowsPath() {
        var service = mock(QueryService.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", List.of("A", "B", "C"));
        result.put("length", 2);
        when(service.shortestPath("A", "C")).thenReturn(result);

        var cmd = new QueryCommand(service);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--shortest-path", "A", "C");

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("Shortest path"), "Should show title");
    }

    @Test
    void cyclesQueryWorks() {
        var service = mock(QueryService.class);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", 1);
        result.put("cycles", List.of(List.of("A", "B", "A")));
        when(service.findCycles(100)).thenReturn(result);

        var cmd = new QueryCommand(service);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(".", "--cycles");

        assertEquals(0, exitCode);
    }
}
