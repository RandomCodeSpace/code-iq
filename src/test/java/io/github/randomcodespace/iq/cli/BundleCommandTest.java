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
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleCommandTest {

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
    void bundleFailsWhenNoCacheExists(@TempDir Path tempDir) {
        var config = new CodeIqConfig();
        config.setCacheDir(".code-intelligence");

        var cmd = new BundleCommand(config);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        assertEquals(1, exitCode);
    }

    @Test
    void bundleCreatesZipFile(@TempDir Path tempDir) throws IOException {
        // Create a fake cache directory
        Path cacheDir = tempDir.resolve(".code-intelligence");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("graph.bin"), "graph-data",
                StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        config.setCacheDir(".code-intelligence");

        Path zipPath = tempDir.resolve("test-bundle.zip");
        var cmd = new BundleCommand(config);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(zipPath), "ZIP file should be created");
        assertTrue(Files.size(zipPath) > 0, "ZIP file should not be empty");
    }
}
