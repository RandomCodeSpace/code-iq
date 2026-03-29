package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.analyzer.AnalysisResult;
import io.github.randomcodespace.iq.analyzer.Analyzer;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
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
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BundleCommandTest {

    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream capture;

    @Mock
    private Analyzer analyzer;

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

    @Test
    void bundleRunsAnalysisWhenNoCacheExists(@TempDir Path tempDir) throws IOException {
        var config = new CodeIqConfig();
        config.setCacheDir(".code-intelligence");

        var result = new AnalysisResult(10, 8, 50, 20, Map.of(), Map.of(), Map.of(), Map.of(), Duration.ofMillis(500));
        when(analyzer.run(any(), any())).thenReturn(result);
        when(flowEngine.renderInteractive(anyString())).thenReturn("<html>flow</html>");

        Path zipPath = tempDir.resolve("test-bundle.zip");
        var cmd = new BundleCommand(config, analyzer, graphStore, flowEngine);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(zipPath), "ZIP file should be created");
        assertTrue(Files.size(zipPath) > 0, "ZIP file should not be empty");
    }

    @Test
    void bundleCreatesZipWithManifestAndFlow(@TempDir Path tempDir) throws IOException {
        // Create a fake cache directory
        Path cacheDir = tempDir.resolve(".code-intelligence");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("graph.bin"), "graph-data",
                StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        config.setCacheDir(".code-intelligence");

        CodeNode node = new CodeNode("n1", NodeKind.CLASS, "MyClass");
        when(graphStore.count()).thenReturn(5L);
        when(graphStore.findAll()).thenReturn(List.of(node));
        when(flowEngine.renderInteractive(anyString())).thenReturn("<html>interactive flow</html>");

        Path zipPath = tempDir.resolve("test-bundle.zip");
        var cmd = new BundleCommand(config, analyzer, graphStore, flowEngine);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString(), "-t", "v1.0");

        assertEquals(0, exitCode);
        assertTrue(Files.exists(zipPath), "ZIP file should be created");

        // Verify ZIP contents
        try (var zf = new ZipFile(zipPath.toFile())) {
            assertNotNull(zf.getEntry("manifest.json"), "Should contain manifest.json");
            assertNotNull(zf.getEntry("flow.html"), "Should contain flow.html");
            assertNotNull(zf.getEntry("graph/graph.bin"), "Should contain graph data");

            // Verify manifest content
            String manifest = new String(
                    zf.getInputStream(zf.getEntry("manifest.json")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertTrue(manifest.contains("\"tag\" : \"v1.0\""), "Manifest should contain tag");
            assertTrue(manifest.contains("\"node_count\" : 5"), "Manifest should contain node count");

            // Verify flow HTML
            String flowHtml = new String(
                    zf.getInputStream(zf.getEntry("flow.html")).readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals("<html>interactive flow</html>", flowHtml);
        }
    }

    @Test
    void bundleHandlesFlowGenerationFailure(@TempDir Path tempDir) throws IOException {
        Path cacheDir = tempDir.resolve(".code-intelligence");
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("data.db"), "db-data", StandardCharsets.UTF_8);

        var config = new CodeIqConfig();
        config.setCacheDir(".code-intelligence");

        when(graphStore.count()).thenReturn(0L);
        when(graphStore.findAll()).thenReturn(List.of());
        when(flowEngine.renderInteractive(anyString()))
                .thenThrow(new RuntimeException("Flow generation failed"));

        Path zipPath = tempDir.resolve("test-bundle.zip");
        var cmd = new BundleCommand(config, analyzer, graphStore, flowEngine);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "-o", zipPath.toString());

        assertEquals(0, exitCode);
        assertTrue(Files.exists(zipPath), "ZIP should still be created even if flow fails");

        try (var zf = new ZipFile(zipPath.toFile())) {
            assertNotNull(zf.getEntry("manifest.json"));
            assertNull(zf.getEntry("flow.html"), "flow.html should be absent when generation fails");
        }
    }
}
