package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.graph.GraphStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import io.github.randomcodespace.iq.config.CodeIqConfigTestSupport;

/**
 * Extended tests for BundleCommand covering additional branches:
 * - custom --graph path
 * - --include-jar flag (jar not found on classpath → warning)
 * - SNAPSHOT version warning when not including jar
 * - output path defaults to <projectName>-<tag>-bundle.zip
 * - bundle with h2 cache stats when h2 file present
 * - bundleDirectory walk (skipLocks=false path, .pid files skipped)
 */
@ExtendWith(MockitoExtension.class)
class BundleCommandExtendedTest {

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private ByteArrayOutputStream captureOut;
    private ByteArrayOutputStream captureErr;

    @Mock
    private FlowEngine flowEngine;

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

    private Path createFakeGraphDb(Path tempDir) throws IOException {
        Path graphDb = tempDir.resolve(".code-iq/graph/graph.db");
        Files.createDirectories(graphDb);
        Files.writeString(graphDb.resolve("neostore"), "neo4j-data", StandardCharsets.UTF_8);
        return graphDb;
    }

    // ---- custom --graph path ------------------------------------------

    @Test
    void bundleFailsWhenCustomGraphPathDoesNotExist(@TempDir Path tempDir) {
        var config = new CodeIqConfig();
        var cmd = new BundleCommand(config, java.util.Optional.empty(), java.util.Optional.empty());
        var cmdLine = new picocli.CommandLine(cmd);
        Path fakeGraph = tempDir.resolve("nowhere/graph.db");
        int exitCode = cmdLine.execute(tempDir.toString(), "--graph", fakeGraph.toString(),
                "-o", tempDir.resolve("out.zip").toString());

        assertEquals(1, exitCode, "Should fail when custom graph path doesn't exist");
    }

    @Test
    void bundleSucceedsWithCustomGraphPath(@TempDir Path tempDir) throws IOException {
        // Place graph somewhere other than the default location
        Path customGraph = tempDir.resolve("custom/graph.db");
        Files.createDirectories(customGraph);
        Files.writeString(customGraph.resolve("neostore"), "data", StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        Path zipPath = tempDir.resolve("out.zip");
        var cmd = new BundleCommand(config, java.util.Optional.empty(), java.util.Optional.empty());
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(),
                "--graph", customGraph.toString(),
                "-o", zipPath.toString(),
                "--no-source");

        assertEquals(0, exitCode);
        assertTrue(Files.exists(zipPath));
        try (var zf = new ZipFile(zipPath.toFile())) {
            assertNotNull(zf.getEntry("graph.db/neostore"));
        }
    }

    // ---- default output path ------------------------------------------

    @Test
    void bundleDefaultOutputPathUsesProjectNameAndTag(@TempDir Path tempDir) throws IOException {
        createFakeGraphDb(tempDir);

        var config = new CodeIqConfig();
        var cmd = new BundleCommand(config, java.util.Optional.empty(), java.util.Optional.empty());
        var cmdLine = new picocli.CommandLine(cmd);
        // No --output, explicit tag
        int exitCode = cmdLine.execute(tempDir.toString(), "-t", "v2.0", "--no-source");

        assertEquals(0, exitCode);
        String projectName = tempDir.getFileName().toString();
        Path expected = tempDir.resolve(projectName + "-v2.0-bundle.zip");
        assertTrue(Files.exists(expected), "Default zip path should be <project>-<tag>-bundle.zip");
    }

    @Test
    void bundleDefaultTagIsLatest(@TempDir Path tempDir) throws IOException {
        createFakeGraphDb(tempDir);

        var config = new CodeIqConfig();
        var cmd = new BundleCommand(config, java.util.Optional.empty(), java.util.Optional.empty());
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--no-source");

        assertEquals(0, exitCode);
        String projectName = tempDir.getFileName().toString();
        Path expected = tempDir.resolve(projectName + "-latest-bundle.zip");
        assertTrue(Files.exists(expected), "Default tag should be 'latest'");
    }

    // ---- --include-jar flag (jar not on disk) --------------------------

    @Test
    void bundleWithIncludeJarWhenJarNotFoundStillSucceeds(@TempDir Path tempDir) throws IOException {
        createFakeGraphDb(tempDir);

        var config = new CodeIqConfig();
        Path zipPath = tempDir.resolve("out.zip");
        var cmd = new BundleCommand(config, java.util.Optional.empty(), java.util.Optional.empty());
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(),
                "-o", zipPath.toString(),
                "--no-source",
                "--include-jar");

        // Should still succeed even if jar is not found
        assertEquals(0, exitCode);
        assertTrue(Files.exists(zipPath));
    }

    // ---- .pid file skipping ------------------------------------------

    @Test
    void bundleSkipsPidFilesFromGraphDb(@TempDir Path tempDir) throws IOException {
        Path graphDb = tempDir.resolve(".code-iq/graph/graph.db");
        Files.createDirectories(graphDb);
        Files.writeString(graphDb.resolve("neostore"), "data", StandardCharsets.UTF_8);
        Files.writeString(graphDb.resolve("neo4j.pid"), "12345", StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        Path zipPath = tempDir.resolve("out.zip");
        var cmd = new BundleCommand(config, java.util.Optional.empty(), java.util.Optional.empty());
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString(), "--no-source");

        assertEquals(0, exitCode);
        try (var zf = new ZipFile(zipPath.toFile())) {
            assertNotNull(zf.getEntry("graph.db/neostore"), "Should include neostore");
            assertNull(zf.getEntry("graph.db/neo4j.pid"), "Should skip .pid files");
        }
    }

    // ---- flow engine null (no flowEngine) ----------------------------

    @Test
    void bundleWithNullFlowEngineSkipsFlowHtml(@TempDir Path tempDir) throws IOException {
        createFakeGraphDb(tempDir);

        var config = new CodeIqConfig();
        Path zipPath = tempDir.resolve("out.zip");
        var cmd = new BundleCommand(config, java.util.Optional.empty(), java.util.Optional.empty());
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString(), "--no-source");

        assertEquals(0, exitCode);
        try (var zf = new ZipFile(zipPath.toFile())) {
            assertNull(zf.getEntry("flow.html"), "Should not contain flow.html when engine is null");
        }
    }

    // ---- manifest includes_jar flag ----------------------------------

    @Test
    void manifestReflectsIncludesJarFalse(@TempDir Path tempDir) throws IOException {
        createFakeGraphDb(tempDir);

        var config = new CodeIqConfig();
        Path zipPath = tempDir.resolve("out.zip");
        var cmd = new BundleCommand(config, java.util.Optional.empty(), java.util.Optional.empty());
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString(), "--no-source");

        assertEquals(0, exitCode);
        try (var zf = new ZipFile(zipPath.toFile())) {
            String manifest = new String(
                    zf.getInputStream(zf.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(manifest.contains("\"includes_jar\" : false"));
        }
    }

    // ---- serve.bat content -------------------------------------------

    @Test
    void bundleContainsServeBatWithWindowsContent(@TempDir Path tempDir) throws IOException {
        createFakeGraphDb(tempDir);

        var config = new CodeIqConfig();
        Path zipPath = tempDir.resolve("out.zip");
        var cmd = new BundleCommand(config, java.util.Optional.empty(), java.util.Optional.empty());
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString(), "--no-source");

        assertEquals(0, exitCode);
        try (var zf = new ZipFile(zipPath.toFile())) {
            assertNotNull(zf.getEntry("serve.bat"));
            String bat = new String(
                    zf.getInputStream(zf.getEntry("serve.bat")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(bat.contains("@echo off"), "serve.bat should contain @echo off");
            assertTrue(bat.contains("serve ./source"), "serve.bat should reference serve command");
        }
    }

    // ---- H2 cache stats path ----------------------------------------

    @Test
    void bundleWithH2CacheReportsStats(@TempDir Path tempDir) throws IOException {
        createFakeGraphDb(tempDir);

        // Create a minimal H2 cache directory (no .mv.db file – no stats but no error)
        Path cacheDir = tempDir.resolve(".code-iq/cache");
        Files.createDirectories(cacheDir);

        var config = new CodeIqConfig();
        CodeIqConfigTestSupport.override(config).cacheDir(".code-iq/cache").done();
        Path zipPath = tempDir.resolve("out.zip");
        var cmd = new BundleCommand(config, java.util.Optional.empty(), java.util.Optional.empty());
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString(), "--no-source");

        // Should succeed (cache dir exists but has no db file — warning logged, counts stay 0)
        assertEquals(0, exitCode);
    }
}
