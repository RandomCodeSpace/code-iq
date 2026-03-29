package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.detector.Detector;
import io.github.randomcodespace.iq.detector.DetectorRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PluginsCommandTest {

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
    void listSubcommandShowsAllDetectors() {
        var d1 = mockDetector("alpha-detector", Set.of("java"));
        var d2 = mockDetector("beta-detector", Set.of("python", "typescript"));
        var registry = new DetectorRegistry(List.of(d1, d2));

        var listCmd = new PluginsCommand.ListSubcommand(registry);
        int exitCode = listCmd.call();

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("alpha-detector"), "Should list alpha-detector");
        assertTrue(output.contains("beta-detector"), "Should list beta-detector");
        assertTrue(output.contains("2"), "Should show detector count");
    }

    @Test
    void listSubcommandShowsSupportedLanguages() {
        var d1 = mockDetector("test-det", Set.of("java", "kotlin"));
        var registry = new DetectorRegistry(List.of(d1));

        var listCmd = new PluginsCommand.ListSubcommand(registry);
        listCmd.call();

        String output = capture.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("java"), "Should list java language");
        assertTrue(output.contains("kotlin"), "Should list kotlin language");
    }

    @Test
    void infoSubcommandReturnsOneForMissingDetector() {
        var registry = new DetectorRegistry(List.of());
        var infoCmd = new PluginsCommand.InfoSubcommand(registry);

        // Use picocli to parse args into the command
        var cmdLine = new picocli.CommandLine(infoCmd);
        int exitCode = cmdLine.execute("nonexistent");

        assertEquals(1, exitCode);
    }

    @Test
    void emptyRegistryShowsZeroCount() {
        var registry = new DetectorRegistry(List.of());
        var listCmd = new PluginsCommand.ListSubcommand(registry);
        int exitCode = listCmd.call();

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("0"), "Should show zero count");
    }

    private Detector mockDetector(String name, Set<String> languages) {
        var d = mock(Detector.class);
        when(d.getName()).thenReturn(name);
        when(d.getSupportedLanguages()).thenReturn(languages);
        return d;
    }
}
