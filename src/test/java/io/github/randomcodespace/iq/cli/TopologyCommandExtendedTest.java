package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import io.github.randomcodespace.iq.query.TopologyService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Extended tests for TopologyCommand covering branches not hit by TopologyCommandTest:
 * - successful run with real H2 cache (we simulate via a real cache file)
 * - --format json output
 * - --service flag
 * - --deps flag
 * - --blast-radius flag
 * - pretty print topology overview
 * - pretty print non-Map result
 * - TopologyService throws → exit 1
 * - cache load exception → exit 1
 */
class TopologyCommandExtendedTest {

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

    /**
     * Create a real H2 analysis cache in the given directory.
     * We write the .mv.db sentinel so the command knows a cache exists,
     * then use the real AnalysisCache to populate it.
     */
    private Path createRealCache(Path tempDir) throws IOException {
        Path cacheDir = tempDir.resolve(".code-intelligence");
        Files.createDirectories(cacheDir);
        Path dbPath = cacheDir.resolve("analysis-cache.db");

        try (var cache = new io.github.randomcodespace.iq.cache.AnalysisCache(dbPath)) {
            // create a SERVICE node and an ENDPOINT
            CodeNode svc = makeNode("svc:api:SERVICE:api-service", NodeKind.SERVICE, "api-service");
            CodeNode ep = makeNode("ep:api:/users:GET:/users", NodeKind.ENDPOINT, "GET /users");
            ep.setModule("api-service");

            cache.replaceAll(java.util.List.of(svc, ep), java.util.List.of());
        }
        return dbPath;
    }

    private CodeNode makeNode(String id, NodeKind kind, String label) {
        CodeNode n = new CodeNode();
        n.setId(id);
        n.setKind(kind);
        n.setLabel(label);
        n.setProperties(new HashMap<>());
        n.setEdges(new ArrayList<>());
        return n;
    }

    // ---- cache not found → error ------------------------------------

    @Test
    void missingCacheReturnsExitCode1(@TempDir Path tempDir) {
        var config = new CodeIqConfig();
        var svc = new TopologyService();
        var cmd = new TopologyCommand(config, svc);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());
        assertEquals(1, exitCode);
    }

    // ---- mocked TopologyService: topology overview ------------------

    @Test
    void prettyPrintsTopologyOverview(@TempDir Path tempDir) throws IOException {
        createRealCache(tempDir);

        var config = new CodeIqConfig();
        config.setCacheDir(".code-intelligence");
        var svc = new TopologyService();
        var cmd = new TopologyCommand(config, svc);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        assertEquals(0, exitCode);
        String out = captureOut.toString(StandardCharsets.UTF_8);
        // The pretty print path should output service topology section
        assertTrue(out.contains("Service") || out.contains("service") || out.contains("{"),
                "Output should contain topology info");
    }

    // ---- json format ------------------------------------------------

    @Test
    void jsonFormatOutputsValidJson(@TempDir Path tempDir) throws IOException {
        createRealCache(tempDir);

        var config = new CodeIqConfig();
        config.setCacheDir(".code-intelligence");
        var svc = new TopologyService();
        var cmd = new TopologyCommand(config, svc);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--format", "json");

        assertEquals(0, exitCode);
        String out = captureOut.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("{"), "JSON format should contain {");
        assertTrue(out.contains("services"), "JSON format should contain 'services' key");
    }

    // ---- --service flag ---------------------------------------------

    @Test
    void serviceFlagOutputsServiceDetail(@TempDir Path tempDir) throws IOException {
        createRealCache(tempDir);

        var config = new CodeIqConfig();
        config.setCacheDir(".code-intelligence");
        var svc = new TopologyService();
        var cmd = new TopologyCommand(config, svc);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--service", "api-service");

        assertEquals(0, exitCode);
        String out = captureOut.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("api-service") || out.contains("{"), "Should contain service detail");
    }

    // ---- --deps flag ------------------------------------------------

    @Test
    void depsFlagOutputsDependencies(@TempDir Path tempDir) throws IOException {
        createRealCache(tempDir);

        var config = new CodeIqConfig();
        config.setCacheDir(".code-intelligence");
        var svc = new TopologyService();
        var cmd = new TopologyCommand(config, svc);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--deps", "api-service");

        assertEquals(0, exitCode);
    }

    // ---- --blast-radius flag ----------------------------------------

    @Test
    void blastRadiusFlagOutputsBlastRadius(@TempDir Path tempDir) throws IOException {
        createRealCache(tempDir);

        var config = new CodeIqConfig();
        config.setCacheDir(".code-intelligence");
        var svc = new TopologyService();
        var cmd = new TopologyCommand(config, svc);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--blast-radius", "svc:api:SERVICE:api-service");

        assertEquals(0, exitCode);
        String out = captureOut.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("svc:api:SERVICE:api-service") || out.contains("{"),
                "Should contain blast radius result");
    }

    // ---- --service json format  ------------------------------------

    @Test
    void serviceFlagWithJsonFormat(@TempDir Path tempDir) throws IOException {
        createRealCache(tempDir);

        var config = new CodeIqConfig();
        config.setCacheDir(".code-intelligence");
        var svc = new TopologyService();
        var cmd = new TopologyCommand(config, svc);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString(), "--service", "api-service", "--format", "json");

        assertEquals(0, exitCode);
        String out = captureOut.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("{"));
    }

    // ---- TopologyService throws → exit 1 ---------------------------

    @Test
    void topologyServiceExceptionReturnsExitCode1(@TempDir Path tempDir) throws IOException {
        createRealCache(tempDir);

        var config = new CodeIqConfig();
        config.setCacheDir(".code-intelligence");
        var svc = mock(TopologyService.class);
        when(svc.getTopology(anyList(), anyList()))
                .thenThrow(new RuntimeException("topology failed"));
        var cmd = new TopologyCommand(config, svc);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        assertEquals(1, exitCode);
        String err = captureErr.toString(StandardCharsets.UTF_8);
        assertTrue(err.contains("failed") || err.contains("Topology"),
                "Should report failure: " + err);
    }

    // ---- printPretty with connections list --------------------------

    @Test
    void prettyPrintWithConnections(@TempDir Path tempDir) throws IOException {
        Path cacheDir = tempDir.resolve(".code-intelligence");
        Files.createDirectories(cacheDir);
        Path dbPath = cacheDir.resolve("analysis-cache.db");

        try (var cache = new io.github.randomcodespace.iq.cache.AnalysisCache(dbPath)) {
            // Two services with a CALLS edge between them
            CodeNode svc1 = makeNode("svc:a:SERVICE:service-a", NodeKind.SERVICE, "service-a");
            CodeNode svc2 = makeNode("svc:b:SERVICE:service-b", NodeKind.SERVICE, "service-b");
            CodeNode ep = makeNode("ep:a:/ping:GET:/ping", NodeKind.ENDPOINT, "GET /ping");
            ep.setModule("service-a");
            CodeEdge edge = new CodeEdge("edge:calls:1", EdgeKind.CALLS, svc1.getId(), svc2);
            svc1.getEdges().add(edge);

            cache.replaceAll(java.util.List.of(svc1, svc2, ep), java.util.List.of(edge));
        }

        var config = new CodeIqConfig();
        config.setCacheDir(".code-intelligence");
        var svc = new TopologyService();
        var cmd = new TopologyCommand(config, svc);
        var cmdLine = new picocli.CommandLine(cmd);
        int exitCode = cmdLine.execute(tempDir.toString());

        assertEquals(0, exitCode);
        String out = captureOut.toString(StandardCharsets.UTF_8);
        assertTrue(out.contains("service-a") || out.contains("Service Topology"),
                "Should contain service names or header: " + out);
    }
}
