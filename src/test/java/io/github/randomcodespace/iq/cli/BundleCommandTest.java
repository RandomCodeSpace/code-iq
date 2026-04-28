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

@ExtendWith(MockitoExtension.class)
class BundleCommandTest {

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream capture;

    @Mock
    private GraphStore graphStore;

    @Mock
    private FlowEngine flowEngine;

    @BeforeEach
    void setUp() {
        capture = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    private void createFakeGraphDb(Path tempDir) throws IOException {
        Path graphDb = tempDir.resolve(".codeiq/graph/graph.db");
        Files.createDirectories(graphDb);
        Files.writeString(graphDb.resolve("neostore"), "neo4j-data", StandardCharsets.UTF_8);
    }

    @Test
    void bundleFailsWhenNoGraphExists(@TempDir Path tempDir) {
        var config = new CodeIqConfig();
        var cmd = new BundleCommand(config, (GraphStore) null, (FlowEngine) null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        assertEquals(1, exitCode, "Should fail when no Neo4j graph exists");
    }

    @Test
    void bundleCreatesZipWithCorrectStructure(@TempDir Path tempDir) throws IOException {
        createFakeGraphDb(tempDir);

        // Create a source file
        Files.writeString(tempDir.resolve("App.java"), "class App {}", StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        CodeIqConfigTestSupport.override(config).cacheDir(".codeiq/cache").done();

        when(flowEngine.renderInteractive(anyString())).thenReturn("<html>flow</html>");

        Path zipPath = tempDir.resolve("test-bundle.zip");
        var cmd = new BundleCommand(config, (GraphStore) null, flowEngine);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString(), "-t", "v1.0");

        assertEquals(0, exitCode);
        assertTrue(Files.exists(zipPath));

        try (var zf = new ZipFile(zipPath.toFile())) {
            assertNotNull(zf.getEntry("manifest.json"), "Should contain manifest.json");
            assertNotNull(zf.getEntry("serve.sh"), "Should contain serve.sh");
            assertNotNull(zf.getEntry("serve.bat"), "Should contain serve.bat");
            assertNotNull(zf.getEntry("graph.db/neostore"), "Should contain Neo4j graph data");
            assertNotNull(zf.getEntry("flow.html"), "Should contain flow.html");

            // Verify manifest
            String manifest = new String(
                    zf.getInputStream(zf.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(manifest.contains("\"tag\" : \"v1.0\""));
            assertTrue(manifest.contains("\"bundle_format\" : 2"));
            assertTrue(manifest.contains("\"backend\" : \"neo4j\""));
            assertTrue(manifest.contains("\"includes_source\" : true"));

            // Verify serve.sh content — air-gapped (no public-internet calls)
            String serveShell = new String(
                    zf.getInputStream(zf.getEntry("serve.sh")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(serveShell.contains("#!/usr/bin/env bash"));
            assertTrue(serveShell.contains("serve ./source"));
            assertTrue(serveShell.contains("--graph ./graph.db"));
            // Defense against re-introduction of network calls in launchers
            assertFalse(serveShell.contains("curl"),
                    "serve.sh must not include any curl/network call (RAN-46 air-gap rule)");
            assertFalse(serveShell.contains("maven.org"),
                    "serve.sh must not reference Maven Central (RAN-46 air-gap rule)");
            assertTrue(serveShell.contains("sha256sum -c"),
                    "serve.sh must verify checksums.sha256 by default");

            // Verify checksums.sha256 entry exists, format-conforms to GNU
            // sha256sum, and excludes itself (would be circular).
            assertNotNull(zf.getEntry("checksums.sha256"),
                    "Bundle must include checksums.sha256");
            String checksums = new String(
                    zf.getInputStream(zf.getEntry("checksums.sha256")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertFalse(checksums.contains("checksums.sha256"),
                    "checksums.sha256 must not list itself (circular)");
            assertTrue(checksums.matches("(?s)([0-9a-f]{64}  \\S.*\n)+"),
                    "Each line must match GNU sha256sum format: <64-hex>  <path>");
            // Sanity: manifest.json should appear in the checksums file.
            assertTrue(checksums.contains("  manifest.json\n"),
                    "Manifest entry must be checksummed");
        }
    }

    @Test
    void bundleSkipsSourceWithNoSourceFlag(@TempDir Path tempDir) throws IOException {
        createFakeGraphDb(tempDir);
        Files.writeString(tempDir.resolve("App.java"), "class App {}", StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        Path zipPath = tempDir.resolve("test-bundle.zip");
        var cmd = new BundleCommand(config, (GraphStore) null, (FlowEngine) null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString(), "--no-source");

        assertEquals(0, exitCode);

        try (var zf = new ZipFile(zipPath.toFile())) {
            assertNotNull(zf.getEntry("manifest.json"));
            assertNotNull(zf.getEntry("graph.db/neostore"));
            // No source/ entries
            assertNull(zf.getEntry("source/App.java"), "Should not contain source when --no-source");

            String manifest = new String(
                    zf.getInputStream(zf.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(manifest.contains("\"includes_source\" : false"));
        }
    }

    @Test
    void bundleHandlesFlowGenerationFailure(@TempDir Path tempDir) throws IOException {
        createFakeGraphDb(tempDir);

        var config = new CodeIqConfig();
        when(flowEngine.renderInteractive(anyString()))
                .thenThrow(new RuntimeException("Flow generation failed"));

        Path zipPath = tempDir.resolve("test-bundle.zip");
        var cmd = new BundleCommand(config, (GraphStore) null, flowEngine);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString());

        assertEquals(0, exitCode, "Bundle should succeed even if flow fails");

        try (var zf = new ZipFile(zipPath.toFile())) {
            assertNotNull(zf.getEntry("manifest.json"));
            assertNull(zf.getEntry("flow.html"), "flow.html should be absent");
        }
    }

    @Test
    void bundleSkipsNeo4jLockFiles(@TempDir Path tempDir) throws IOException {
        Path graphDb = tempDir.resolve(".codeiq/graph/graph.db");
        Files.createDirectories(graphDb);
        Files.writeString(graphDb.resolve("neostore"), "data", StandardCharsets.UTF_8);
        Files.writeString(graphDb.resolve("store_lock"), "locked", StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        Path zipPath = tempDir.resolve("test-bundle.zip");
        var cmd = new BundleCommand(config, (GraphStore) null, (FlowEngine) null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString(), "--no-source");

        assertEquals(0, exitCode);

        try (var zf = new ZipFile(zipPath.toFile())) {
            assertNotNull(zf.getEntry("graph.db/neostore"), "Should include graph data");
            assertNull(zf.getEntry("graph.db/store_lock"), "Should skip lock files");
        }
    }

    @Test
    void bundleIncludesH2Cache(@TempDir Path tempDir) throws IOException {
        createFakeGraphDb(tempDir);

        Path cacheDir = tempDir.resolve(".codeiq/cache");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("analysis-cache.db"), "h2-data", StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        CodeIqConfigTestSupport.override(config).cacheDir(".codeiq/cache").done();

        Path zipPath = tempDir.resolve("test-bundle.zip");
        var cmd = new BundleCommand(config, (GraphStore) null, (FlowEngine) null);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString(), "--no-source");

        assertEquals(0, exitCode);

        try (var zf = new ZipFile(zipPath.toFile())) {
            assertNotNull(zf.getEntry("cache/analysis-cache.db"), "Should include H2 cache");
        }
    }
}
