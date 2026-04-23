package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import io.github.randomcodespace.iq.query.StatsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import io.github.randomcodespace.iq.config.CodeIqConfigTestSupport;

class StatsCommandTest {

    private final PrintStream originalOut = System.out;
    private final PrintStream originalErr = System.err;
    private ByteArrayOutputStream capture;
    private ByteArrayOutputStream captureErr;
    private StatsService statsService;
    private CodeIqConfig config;

    @BeforeEach
    void setUp() {
        capture = new ByteArrayOutputStream();
        captureErr = new ByteArrayOutputStream();
        System.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(captureErr, true, StandardCharsets.UTF_8));
        statsService = new StatsService();
        config = new CodeIqConfig();
        CodeIqConfigTestSupport.override(config).cacheDir(".code-iq/cache").done();
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    /** Get combined stdout + stderr output. */
    private String allOutput() {
        return capture.toString(StandardCharsets.UTF_8)
                + captureErr.toString(StandardCharsets.UTF_8);
    }

    private void populateCache(Path root) {
        Path cachePath = root.resolve(".code-iq/cache").resolve("analysis-cache.db");
        try (AnalysisCache cache = new AnalysisCache(cachePath)) {
            // Create some sample nodes
            var n1 = new CodeNode("n1", NodeKind.CLASS, "UserService");
            n1.setFilePath("src/UserService.java");
            n1.setProperties(new HashMap<>(Map.of("framework", "Spring")));

            var n2 = new CodeNode("n2", NodeKind.ENDPOINT, "getUsers");
            n2.setFilePath("src/UserController.java");
            n2.setProperties(new HashMap<>(Map.of("http_method", "GET")));

            var n3 = new CodeNode("n3", NodeKind.GUARD, "authGuard");
            n3.setFilePath("src/SecurityConfig.java");
            n3.setProperties(new HashMap<>(Map.of("auth_type", "spring_security")));

            var n4 = new CodeNode("n4", NodeKind.METHOD, "findById");
            n4.setFilePath("src/UserService.java");
            n4.setProperties(new HashMap<>());

            // Create some sample edges
            var target = new CodeNode("n2", NodeKind.ENDPOINT, "getUsers");
            var edge = new CodeEdge("e1", EdgeKind.CALLS, "n1", target);

            cache.storeResults("hash1", "src/UserService.java", "java",
                    List.of(n1, n4), List.of(edge));
            cache.storeResults("hash2", "src/UserController.java", "java",
                    List.of(n2), List.of());
            cache.storeResults("hash3", "src/SecurityConfig.java", "java",
                    List.of(n3), List.of());
        }
    }

    // --- No cache found ---

    @Test
    void returnsErrorWhenNoCacheExists(@TempDir Path tempDir) {
        var cmd = new StatsCommand(statsService, config);
        var cmdLine = new CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        assertEquals(1, exitCode);
        String output = allOutput();
        assertTrue(output.contains("No analysis cache found"));
    }

    // --- Pretty format ---

    @Test
    void prettyFormatShowsAllSections(@TempDir Path tempDir) {
        populateCache(tempDir);
        var cmd = new StatsCommand(statsService, config);
        cmd.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
        var cmdLine = new CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        assertEquals(0, exitCode);
        String output = capture.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("Graph:"), "Should show Graph section");
        assertTrue(output.contains("nodes"), "Should mention nodes");
        assertTrue(output.contains("Languages:"), "Should show Languages section");
        assertTrue(output.contains("java"), "Should detect java language");
        assertTrue(output.contains("Architecture:"), "Should show Architecture section");
    }

    // --- JSON format ---

    @Test
    void jsonFormatProducesValidJson(@TempDir Path tempDir) {
        populateCache(tempDir);
        var cmd = new StatsCommand(statsService, config);
        cmd.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
        var cmdLine = new CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--format", "json");

        assertEquals(0, exitCode);
        String output = capture.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\"graph\""), "JSON should contain graph key");
        assertTrue(output.contains("\"architecture\""), "JSON should contain architecture key");
        // Validate it's parseable JSON
        assertDoesNotThrow(() -> new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(output, Map.class));
    }

    // --- YAML format ---

    @Test
    void yamlFormatProducesYaml(@TempDir Path tempDir) {
        populateCache(tempDir);
        var cmd = new StatsCommand(statsService, config);
        cmd.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
        var cmdLine = new CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--format", "yaml");

        assertEquals(0, exitCode);
        String output = capture.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("graph:"), "YAML should contain graph key");
        assertTrue(output.contains("architecture:"), "YAML should contain architecture key");
    }

    // --- Markdown format ---

    @Test
    void markdownFormatProducesTables(@TempDir Path tempDir) {
        populateCache(tempDir);
        var cmd = new StatsCommand(statsService, config);
        cmd.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
        var cmdLine = new CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--format", "markdown");

        assertEquals(0, exitCode);
        String output = capture.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("# Code IQ Stats"), "Should have markdown header");
        assertTrue(output.contains("## Graph"), "Should have Graph section");
        assertTrue(output.contains("| Metric |"), "Should have table headers");
    }

    // --- Category filter ---

    @Test
    void categoryFilterShowsOnlySelectedCategory(@TempDir Path tempDir) {
        populateCache(tempDir);
        var cmd = new StatsCommand(statsService, config);
        cmd.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
        var cmdLine = new CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--format", "json", "--category", "architecture");

        assertEquals(0, exitCode);
        String output = capture.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\"architecture\""), "Should contain architecture");
        assertFalse(output.contains("\"graph\""), "Should not contain graph");
        assertFalse(output.contains("\"frameworks\""), "Should not contain frameworks");
    }

    @Test
    void invalidCategoryReturnsError(@TempDir Path tempDir) {
        populateCache(tempDir);
        var cmd = new StatsCommand(statsService, config);
        var cmdLine = new CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--category", "bogus");

        assertEquals(1, exitCode);
    }

    @Test
    void invalidFormatReturnsError(@TempDir Path tempDir) {
        populateCache(tempDir);
        var cmd = new StatsCommand(statsService, config);
        var cmdLine = new CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--format", "bogus");

        assertEquals(1, exitCode);
    }

    // --- Connections category ---

    @Test
    void connectionsCategoryShowsEndpoints(@TempDir Path tempDir) {
        populateCache(tempDir);
        var cmd = new StatsCommand(statsService, config);
        cmd.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
        var cmdLine = new CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--format", "json", "--category", "connections");

        assertEquals(0, exitCode);
        String output = capture.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\"connections\""), "Should contain connections");
        assertTrue(output.contains("\"rest\""), "Should contain rest");
    }

    // --- Auth category ---

    @Test
    void authCategoryShowsGuards(@TempDir Path tempDir) {
        populateCache(tempDir);
        var cmd = new StatsCommand(statsService, config);
        cmd.setOut(new PrintStream(capture, true, StandardCharsets.UTF_8));
        var cmdLine = new CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--format", "json", "--category", "auth");

        assertEquals(0, exitCode);
        String output = capture.toString(StandardCharsets.UTF_8);
        assertTrue(output.contains("\"auth\""), "Should contain auth");
        assertTrue(output.contains("spring_security"), "Should contain spring_security");
    }
}
