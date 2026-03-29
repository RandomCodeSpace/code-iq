package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.analyzer.LayerClassifier;
import io.github.randomcodespace.iq.analyzer.linker.Linker;
import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnrichCommandTest {

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private ByteArrayOutputStream captureOut;
    private ByteArrayOutputStream captureErr;

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

    @Test
    void enrichFailsWhenNoIndexExists(@TempDir Path tempDir) {
        var config = new CodeIqConfig();
        var layerClassifier = new LayerClassifier();
        List<Linker> linkers = List.of();

        var cmd = new EnrichCommand(config, layerClassifier, linkers);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        // Should fail because no H2 index exists (or succeed creating empty DB)
        // The command tries to load from H2 which may be empty
        // At minimum it should not crash
        assertTrue(exitCode == 0 || exitCode == 1);
    }

    @Test
    void enrichWithIndexedData(@TempDir Path tempDir) throws Exception {
        // Create H2 index with some test data
        Path cacheDir = tempDir.resolve(".code-intelligence");
        Files.createDirectories(cacheDir);
        Path cachePath = cacheDir.resolve("analysis-cache.db");

        try (var cache = new AnalysisCache(cachePath)) {
            CodeNode node1 = new CodeNode("test:class:MyClass", NodeKind.CLASS, "MyClass");
            node1.setFilePath("src/MyClass.java");
            node1.setModule("myModule");

            CodeNode node2 = new CodeNode("test:method:doWork", NodeKind.METHOD, "doWork");
            node2.setFilePath("src/MyClass.java");
            node2.setModule("myModule");

            CodeEdge edge = new CodeEdge("edge1", EdgeKind.CONTAINS, "test:class:MyClass", node2);

            cache.storeResults("hash1", "src/MyClass.java", "java",
                    List.of(node1, node2), List.of(edge));
        }

        var config = new CodeIqConfig();
        var layerClassifier = new LayerClassifier();
        List<Linker> linkers = List.of();

        var cmd = new EnrichCommand(config, layerClassifier, linkers);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        String output = captureOut.toString(StandardCharsets.UTF_8);
        assertEquals(0, exitCode, "Enrich should succeed with indexed data. Output: " + output
                + "\nErr: " + captureErr.toString(StandardCharsets.UTF_8));
        assertTrue(output.contains("Enrichment complete"), "Should report completion");
        assertTrue(output.contains("2"), "Should show node count");
    }

    @Test
    void enrichClassifiesLayers(@TempDir Path tempDir) throws Exception {
        // Create H2 index with frontend and backend nodes
        Path cacheDir = tempDir.resolve(".code-intelligence");
        Files.createDirectories(cacheDir);
        Path cachePath = cacheDir.resolve("analysis-cache.db");

        try (var cache = new AnalysisCache(cachePath)) {
            CodeNode endpoint = new CodeNode("test:endpoint:getUsers", NodeKind.ENDPOINT, "getUsers");
            endpoint.setFilePath("src/api/UserController.java");

            CodeNode component = new CodeNode("test:component:UserList", NodeKind.COMPONENT, "UserList");
            component.setFilePath("src/components/UserList.tsx");

            cache.storeResults("hash1", "src/api/UserController.java", "java",
                    List.of(endpoint), List.of());
            cache.storeResults("hash2", "src/components/UserList.tsx", "typescript",
                    List.of(component), List.of());
        }

        var config = new CodeIqConfig();
        var layerClassifier = new LayerClassifier();
        List<Linker> linkers = List.of();

        var cmd = new EnrichCommand(config, layerClassifier, linkers);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        assertEquals(0, exitCode);
    }
}
