package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.config.CodeIqConfig;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.github.randomcodespace.iq.config.CodeIqConfigTestSupport;

class CacheCommandTest {

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
    void statsShowsNoCacheWhenMissing(@TempDir Path tempDir) {
        var config = new CodeIqConfig();
        CodeIqConfigTestSupport.override(config).cacheDir(".code-iq/cache").done();
        var cmd = new CacheCommand.StatsSubcommand(config);

        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("No cache found"), "Should report no cache");
    }

    @Test
    void statsShowsCacheInfo(@TempDir Path tempDir) throws IOException {
        // Create a fake cache directory with a file
        Path cacheDir = tempDir.resolve(".code-iq/cache");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("test.txt"), "hello world",
                StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        CodeIqConfigTestSupport.override(config).cacheDir(".code-iq/cache").done();
        var cmd = new CacheCommand.StatsSubcommand(config);

        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("Files"), "Should show file count");
        assertTrue(output.contains("Size"), "Should show size");
    }

    @Test
    void clearRemovesCache(@TempDir Path tempDir) throws IOException {
        Path cacheDir = tempDir.resolve(".code-iq/cache");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("data.bin"), "data",
                StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        CodeIqConfigTestSupport.override(config).cacheDir(".code-iq/cache").done();
        var cmd = new CacheCommand.ClearSubcommand(config);

        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        assertEquals(0, exitCode);
        assertFalse(Files.exists(cacheDir), "Cache directory should be removed");
    }

    @Test
    void clearHandlesNoCacheGracefully(@TempDir Path tempDir) {
        var config = new CodeIqConfig();
        CodeIqConfigTestSupport.override(config).cacheDir(".code-iq/cache").done();
        var cmd = new CacheCommand.ClearSubcommand(config);

        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        String output = capture.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode);
        assertTrue(output.contains("No cache to clear"), "Should handle missing cache");
    }
}
