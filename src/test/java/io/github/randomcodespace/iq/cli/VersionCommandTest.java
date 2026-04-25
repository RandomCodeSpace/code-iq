package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.detector.Detector;
import io.github.randomcodespace.iq.detector.DetectorRegistry;
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

class VersionCommandTest {

    @Test
    void versionOutputContainsExpectedInfo() {
        var detector = mock(Detector.class);
        when(detector.getName()).thenReturn("test-detector");
        when(detector.getSupportedLanguages()).thenReturn(Set.of("java", "python"));

        var registry = new DetectorRegistry(List.of(detector));
        var cmd = new VersionCommand(registry, null);

        var out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

        int exitCode = cmd.call();

        String output = out.toString(StandardCharsets.UTF_8);
        System.setOut(System.out);

        assertEquals(0, exitCode);
        assertTrue(output.contains("codeiq"), "Should contain product name");
        assertTrue(output.contains("Detectors"), "Should mention detectors");
        assertTrue(output.contains("Languages"), "Should mention languages");
        assertTrue(output.contains("Java"), "Should mention Java runtime");
    }

    @Test
    void exitCodeIsZero() {
        var registry = new DetectorRegistry(List.of());
        var cmd = new VersionCommand(registry, null);

        var out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));

        int exitCode = cmd.call();
        System.setOut(System.out);

        assertEquals(0, exitCode);
    }
}
