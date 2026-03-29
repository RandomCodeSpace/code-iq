package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.analyzer.AnalysisResult;
import io.github.randomcodespace.iq.analyzer.Analyzer;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyzeCommandTest {

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
    @SuppressWarnings("unchecked")
    void analyzeRunsSuccessfully(@TempDir Path tempDir) {
        var analyzer = mock(Analyzer.class);
        var config = new CodeIqConfig();

        var result = new AnalysisResult(
                42, 38, 120, 85,
                Map.of("java", 20, "python", 15, "yaml", 7),
                Map.of("class", 50, "method", 40, "endpoint", 30),
                Map.of("calls", 50, "contains", 35), Map.of("spring", 30),
                Duration.ofMillis(1234)
        );
        when(analyzer.run(any(Path.class), any(), anyBoolean(), any(Consumer.class))).thenReturn(result);

        var cmd = new AnalyzeCommand(analyzer, config);

        // Use picocli to set the path parameter
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("Analysis complete"), "Should report completion");
        assertTrue(output.contains("120"), "Should show node count");
        assertTrue(output.contains("85"), "Should show edge count");
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeWithParallelismFlag(@TempDir Path tempDir) {
        var analyzer = mock(Analyzer.class);
        var config = new CodeIqConfig();

        var result = new AnalysisResult(
                10, 8, 20, 15,
                Map.of("java", 10),
                Map.of("class", 20),
                Map.of("calls", 15), Map.of(),
                Duration.ofMillis(500)
        );
        when(analyzer.run(any(Path.class), any(), anyBoolean(), any(Consumer.class))).thenReturn(result);

        var cmd = new AnalyzeCommand(analyzer, config);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--parallelism", "4");

        assertEquals(0, exitCode);
        verify(analyzer).run(any(Path.class), eq(4), eq(true), any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeWithoutParallelismPassesNull(@TempDir Path tempDir) {
        var analyzer = mock(Analyzer.class);
        var config = new CodeIqConfig();

        var result = new AnalysisResult(
                10, 8, 20, 15,
                Map.of("java", 10),
                Map.of("class", 20),
                Map.of("calls", 15), Map.of(),
                Duration.ofMillis(500)
        );
        when(analyzer.run(any(Path.class), any(), anyBoolean(), any(Consumer.class))).thenReturn(result);

        var cmd = new AnalyzeCommand(analyzer, config);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        assertEquals(0, exitCode);
        verify(analyzer).run(any(Path.class), eq(null), eq(true), any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeCallsAnalyzerWithCorrectPath(@TempDir Path tempDir) {
        var analyzer = mock(Analyzer.class);
        var config = new CodeIqConfig();

        var result = new AnalysisResult(
                0, 0, 0, 0,
                Map.of(), Map.of(), Map.of(), Map.of(), Duration.ZERO
        );
        when(analyzer.run(any(Path.class), any(), anyBoolean(), any(Consumer.class))).thenReturn(result);

        var cmd = new AnalyzeCommand(analyzer, config);
        var cmdLine = new picocli.CommandLine(cmd);
        cmdLine.execute(tempDir.toString());

        verify(analyzer).run(eq(tempDir.toAbsolutePath().normalize()), eq(null), eq(true), any(Consumer.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void analyzeWithNoCacheDisablesIncremental(@TempDir Path tempDir) {
        var analyzer = mock(Analyzer.class);
        var config = new CodeIqConfig();

        var result = new AnalysisResult(
                5, 5, 10, 5,
                Map.of("java", 5),
                Map.of("class", 10),
                Map.of("calls", 5), Map.of(),
                Duration.ofMillis(200)
        );
        when(analyzer.run(any(Path.class), any(), anyBoolean(), any(Consumer.class))).thenReturn(result);

        var cmd = new AnalyzeCommand(analyzer, config);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--no-cache");

        assertEquals(0, exitCode);
        verify(analyzer).run(any(Path.class), eq(null), eq(false), any(Consumer.class));
    }
}
