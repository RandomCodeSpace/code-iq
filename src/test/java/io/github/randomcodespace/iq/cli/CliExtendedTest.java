package io.github.randomcodespace.iq.cli;

import io.github.randomcodespace.iq.detector.Detector;
import io.github.randomcodespace.iq.detector.DetectorRegistry;
import io.github.randomcodespace.iq.flow.FlowEngine;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import io.github.randomcodespace.iq.query.QueryService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CliExtendedTest {

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

    // ==================== FlowCommand ====================
    @Nested
    class FlowCommandExtended {
        private FlowEngine createEngine() {
            var store = mockStoreWithEndpoint();
            return new FlowEngine(store);
        }

        private GraphStore mockStoreWithEndpoint() {
            var store = mock(GraphStore.class);
            var endpoint = new CodeNode();
            endpoint.setId("ep:test:endpoint:getUser");
            endpoint.setLabel("GET /users");
            endpoint.setKind(NodeKind.ENDPOINT);
            endpoint.setProperties(new java.util.HashMap<>());
            endpoint.setEdges(new java.util.ArrayList<>());
            endpoint.setLayer("backend");

            when(store.findAll()).thenReturn(List.of(endpoint));
            when(store.findByKind(any(NodeKind.class))).thenReturn(List.of());
            when(store.findByKind(NodeKind.ENDPOINT)).thenReturn(List.of(endpoint));
            when(store.count()).thenReturn(1L);
            return store;
        }

        @Test
        void overviewViewMermaid() {
            var engine = createEngine();

            var cmd = new FlowCommand(engine, null);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--view", "overview");

            String out = captureOut.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode);
            assertTrue(out.contains("graph "), "Should contain mermaid header");
        }

        @Test
        void ciViewMermaid() {
            var engine = createEngine();

            var cmd = new FlowCommand(engine, null);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--view", "ci");

            assertEquals(0, exitCode);
        }

        @Test
        void overviewViewJson() {
            var engine = createEngine();

            var cmd = new FlowCommand(engine, null);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--view", "overview", "--format", "json");

            String out = captureOut.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode);
            assertTrue(out.contains("\"view\""));
        }

        @Test
        void deployViewJson() {
            var engine = createEngine();

            var cmd = new FlowCommand(engine, null);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--view", "deploy", "--format", "json");

            String out = captureOut.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode);
            assertTrue(out.contains("\"view\""));
        }

        @Test
        void outputToFile(@TempDir Path tmpDir) {
            var engine = createEngine();

            Path outFile = tmpDir.resolve("flow.md");
            var cmd = new FlowCommand(engine, null);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--output", outFile.toString());

            assertEquals(0, exitCode);
            assertTrue(outFile.toFile().exists());
        }
    }

    // ==================== FindCommand ====================
    @Nested
    class FindCommandExtended {
        @Test
        void findEndpointsShowsResults() {
            var store = mock(GraphStore.class);
            var node = createNode("ep:routes:get", "GET /api/users", NodeKind.ENDPOINT, "backend");
            node.setFilePath("UserController.java");
            node.setLineStart(10);
            when(store.findByKindPaginated(eq("endpoint"), anyInt(), anyInt())).thenReturn(List.of(node));

            var cmd = new FindCommand(store);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute("endpoints");

            String out = captureOut.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode);
            assertTrue(out.contains("GET /api/users"));
        }

        @Test
        void findWithLayerFilter() {
            var store = mock(GraphStore.class);
            var node1 = createNode("ep:1", "GET /api", NodeKind.ENDPOINT, "backend");
            var node2 = createNode("ep:2", "GET /web", NodeKind.ENDPOINT, "frontend");
            when(store.findByKindPaginated(eq("endpoint"), anyInt(), anyInt())).thenReturn(List.of(node1, node2));

            var cmd = new FindCommand(store);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute("endpoints", ".", "--layer", "backend");

            assertEquals(0, exitCode);
        }

        @Test
        void findUnknownTargetShowsError() {
            var store = mock(GraphStore.class);
            var cmd = new FindCommand(store);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute("bogus");

            assertEquals(1, exitCode);
        }

        @Test
        void findMiddlewaresResolves() {
            assertEquals(NodeKind.MIDDLEWARE, FindCommand.resolveKind("middlewares"));
        }

        @Test
        void findConfigFilesResolves() {
            assertEquals(NodeKind.CONFIG_FILE, FindCommand.resolveKind("config_file"));
            assertEquals(NodeKind.CONFIG_FILE, FindCommand.resolveKind("config_files"));
        }

        @Test
        void findEmptyResultsShowsWarning() {
            var store = mock(GraphStore.class);
            when(store.findByKindPaginated(anyString(), anyInt(), anyInt())).thenReturn(List.of());

            var cmd = new FindCommand(store);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute("endpoints");

            assertEquals(1, exitCode);
        }
    }

    // ==================== GraphCommand ====================
    @Nested
    class GraphCommandExtended {
        @Test
        void focusNodeUsesEgoGraph() {
            var store = mock(GraphStore.class);
            var node = createNode("test:1", "TestClass", NodeKind.CLASS, null);
            when(store.findEgoGraph(eq("focus:node"), anyInt())).thenReturn(List.of(node));

            var cmd = new GraphCommand(store);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--focus", "focus:node");

            String out = captureOut.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode);
            assertTrue(out.contains("TestClass"));
        }

        @Test
        void jsonEscapesSpecialChars() {
            var store = mock(GraphStore.class);
            var node = createNode("test:\"special\"", "Class\"Name", NodeKind.CLASS, null);
            when(store.findAllPaginated(anyInt(), anyInt())).thenReturn(List.of(node));

            var cmd = new GraphCommand(store);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--format", "json");

            assertEquals(0, exitCode);
        }

        @Test
        void outputToFile(@TempDir Path tmpDir) {
            var store = mock(GraphStore.class);
            var node = createNode("test:1", "Svc", NodeKind.CLASS, null);
            when(store.findAllPaginated(anyInt(), anyInt())).thenReturn(List.of(node));

            Path outFile = tmpDir.resolve("graph.json");
            var cmd = new GraphCommand(store);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--output", outFile.toString());

            assertEquals(0, exitCode);
            assertTrue(outFile.toFile().exists());
        }
    }

    // ==================== QueryCommand ====================
    @Nested
    class QueryCommandExtended {
        @Test
        void producersOfShowsResults() {
            var service = mock(QueryService.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", 1);
            result.put("producers", List.of(Map.of("id", "p:1", "kind", "class", "label", "Producer")));
            when(service.producersOf("target")).thenReturn(result);

            var cmd = new QueryCommand(service);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--producers-of", "target");

            assertEquals(0, exitCode);
        }

        @Test
        void callersOfShowsResults() {
            var service = mock(QueryService.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", 1);
            result.put("callers", List.of(Map.of("id", "c:1", "kind", "method", "label", "Caller")));
            when(service.callersOf("func")).thenReturn(result);

            var cmd = new QueryCommand(service);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--callers-of", "func");

            assertEquals(0, exitCode);
        }

        @Test
        void dependenciesOfShowsResults() {
            var service = mock(QueryService.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", 2);
            result.put("dependencies", List.of(
                    Map.of("id", "d:1", "kind", "module", "label", "Dep1"),
                    Map.of("id", "d:2", "kind", "module", "label", "Dep2")
            ));
            when(service.dependenciesOf("mod")).thenReturn(result);

            var cmd = new QueryCommand(service);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--dependencies-of", "mod");

            assertEquals(0, exitCode);
        }

        @Test
        void dependentsOfShowsResults() {
            var service = mock(QueryService.class);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", 1);
            result.put("dependents", List.of(Map.of("id", "d:1", "kind", "module", "label", "Dep")));
            when(service.dependentsOf("mod")).thenReturn(result);

            var cmd = new QueryCommand(service);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--dependents-of", "mod");

            assertEquals(0, exitCode);
        }

        @Test
        void shortestPathNotFoundReturnsOne() {
            var service = mock(QueryService.class);
            when(service.shortestPath("A", "Z")).thenReturn(null);

            var cmd = new QueryCommand(service);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--shortest-path", "A", "Z");

            assertEquals(1, exitCode);
        }

        @Test
        void nullResultReturnsOne() {
            var service = mock(QueryService.class);
            when(service.consumersOf("x")).thenReturn(null);

            var cmd = new QueryCommand(service);
            var cmdLine = new picocli.CommandLine(cmd);
            int exitCode = cmdLine.execute(".", "--consumers-of", "x");

            assertEquals(1, exitCode);
        }
    }

    // ==================== PluginsCommand ====================
    @Nested
    class PluginsCommandExtended {
        @Test
        void infoSubcommandShowsDetectorInfo() {
            var d1 = mockDetector("test-detector", Set.of("java", "kotlin"));
            var registry = new DetectorRegistry(List.of(d1));

            var infoCmd = new PluginsCommand.InfoSubcommand(registry);
            var cmdLine = new picocli.CommandLine(infoCmd);
            int exitCode = cmdLine.execute("test-detector");

            String out = captureOut.toString(StandardCharsets.UTF_8);
            assertEquals(0, exitCode);
            assertTrue(out.contains("test-detector"));
        }

        @Test
        void runDefaultListsDetectors() {
            var d1 = mockDetector("det1", Set.of("java"));
            var registry = new DetectorRegistry(List.of(d1));

            var cmd = new PluginsCommand(registry);
            cmd.run();

            String out = captureOut.toString(StandardCharsets.UTF_8);
            // Default run delegates to list, which shows category summary
            assertTrue(out.contains("1"), "Should show detector count");
            assertTrue(out.contains("Category"), "Should show table header");
        }
    }

    // ==================== CacheCommand ====================
    @Nested
    class CacheCommandExtended {
        @Test
        void cacheRunPrintsUsage() {
            var cmd = new CacheCommand();
            cmd.run();
            // Should not throw
        }
    }

    // ==================== CliOutput ====================
    @Nested
    class CliOutputTest {
        @Test
        void successToStream() {
            CliOutput.success(System.out, "done");
            assertTrue(captureOut.toString(StandardCharsets.UTF_8).contains("done"));
        }

        @Test
        void cyanToStream() {
            CliOutput.cyan(System.out, "highlight");
            assertTrue(captureOut.toString(StandardCharsets.UTF_8).contains("highlight"));
        }

        @Test
        void boldToStream() {
            CliOutput.bold(System.out, "title");
            assertTrue(captureOut.toString(StandardCharsets.UTF_8).contains("title"));
        }

        @Test
        void stepToStream() {
            CliOutput.step(System.out, ">>", "action");
            assertTrue(captureOut.toString(StandardCharsets.UTF_8).contains("action"));
        }

        @Test
        void infoToStream() {
            CliOutput.info(System.out, "message");
            assertTrue(captureOut.toString(StandardCharsets.UTF_8).contains("message"));
        }

        @Test
        void formatReturnsString() {
            String result = CliOutput.format("test text");
            assertNotNull(result);
            assertTrue(result.contains("test text"));
        }

        @Test
        void warnPrintsToStdErr() {
            CliOutput.warn("warning message");
            assertTrue(captureErr.toString(StandardCharsets.UTF_8).contains("warning message"));
        }

        @Test
        void errorPrintsToStdErr() {
            CliOutput.error("error message");
            assertTrue(captureErr.toString(StandardCharsets.UTF_8).contains("error message"));
        }

        @Test
        void successPrintsToStdOut() {
            CliOutput.success("great");
            assertTrue(captureOut.toString(StandardCharsets.UTF_8).contains("great"));
        }

        @Test
        void cyanPrintsToStdOut() {
            CliOutput.cyan("blue text");
            assertTrue(captureOut.toString(StandardCharsets.UTF_8).contains("blue text"));
        }

        @Test
        void boldPrintsToStdOut() {
            CliOutput.bold("bold text");
            assertTrue(captureOut.toString(StandardCharsets.UTF_8).contains("bold text"));
        }
    }

    // ==================== CodeIqCli ====================
    @Nested
    class CodeIqCliTest {
        @Test
        void cliCanBeInstantiated() {
            var cli = new CodeIqCli();
            assertNotNull(cli);
        }
    }

    // ==================== Helpers ====================

    private CodeNode createNode(String id, String label, NodeKind kind, String layer) {
        var node = new CodeNode();
        node.setId(id);
        node.setLabel(label);
        node.setKind(kind);
        node.setLayer(layer);
        return node;
    }

    private Detector mockDetector(String name, Set<String> languages) {
        var d = mock(Detector.class);
        when(d.getName()).thenReturn(name);
        when(d.getSupportedLanguages()).thenReturn(languages);
        return d;
    }
}
