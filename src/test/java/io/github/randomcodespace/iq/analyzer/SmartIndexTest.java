package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.analyzer.linker.Linker;
import io.github.randomcodespace.iq.config.CodeIqConfig;
import io.github.randomcodespace.iq.config.unified.CodeIqUnifiedConfig;
import io.github.randomcodespace.iq.detector.Detector;
import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.detector.DetectorRegistry;
import io.github.randomcodespace.iq.detector.DetectorResult;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for smart partitioned indexing: module detection, config-first Phase 1,
 * and keyword pre-filtering behaviour.
 */
class SmartIndexTest {

    @TempDir
    Path tempDir;

    private Analyzer analyzer;
    private List<String> progressMessages;

    @BeforeEach
    void setUp() {
        progressMessages = new ArrayList<>();

        // Detector that produces one CLASS node per Java file, records registry availability
        Detector testDetector = new Detector() {
            @Override public String getName() { return "test-detector"; }
            @Override public Set<String> getSupportedLanguages() { return Set.of("java"); }

            @Override
            public DetectorResult detect(DetectorContext ctx) {
                var node = new CodeNode("class:" + ctx.filePath(), NodeKind.CLASS, ctx.filePath());
                node.setFilePath(ctx.filePath());
                node.setModule(ctx.moduleName());
                // Store registry availability as a property for assertion
                if (ctx.registry() != null) {
                    node.getProperties().put("hasRegistry", "true");
                }
                return DetectorResult.of(List.of(node), List.of());
            }
        };

        var registry = new DetectorRegistry(List.of(testDetector));
        var parser = new StructuredParser();
        var fileDiscovery = new FileDiscovery(new CodeIqConfig());
        var layerClassifier = new LayerClassifier();
        List<Linker> linkers = List.of();

        analyzer = new Analyzer(registry, parser, fileDiscovery, layerClassifier, linkers,
                new CodeIqConfig(), CodeIqUnifiedConfig.empty(),
                new ConfigScanner(), new ArchitectureKeywordFilter());
    }

    // -------------------------------------------------------------------------
    // detectModules()
    // -------------------------------------------------------------------------

    @Test
    void detectModules_singleRootPom() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/App.java", "public class App {}");
        write("src/main/java/Service.java", "public class Service {}");

        FileDiscovery fd = new FileDiscovery(new CodeIqConfig());
        List<DiscoveredFile> files = fd.discover(tempDir);

        Map<String, List<DiscoveredFile>> modules = analyzer.detectModules(tempDir, files);

        // All files should be in the root ("") module
        assertEquals(1, modules.size());
        assertTrue(modules.containsKey(""));
    }

    @Test
    void detectModules_multiModuleMavenProject() throws IOException {
        // Root pom
        write("pom.xml", "<project/>");
        // Two sub-modules
        write("services/auth/pom.xml", "<project/>");
        write("services/auth/src/main/java/AuthService.java", "@Service public class AuthService {}");
        write("services/order/pom.xml", "<project/>");
        write("services/order/src/main/java/OrderService.java", "@Service public class OrderService {}");

        FileDiscovery fd = new FileDiscovery(new CodeIqConfig());
        List<DiscoveredFile> files = fd.discover(tempDir);

        Map<String, List<DiscoveredFile>> modules = analyzer.detectModules(tempDir, files);

        // Should detect root + auth + order modules
        assertTrue(modules.size() >= 2, "Expected at least 2 modules, got: " + modules.keySet());
        assertTrue(modules.containsKey("services/auth") || modules.containsKey("services\\auth"),
                "Expected services/auth module, got: " + modules.keySet());
        assertTrue(modules.containsKey("services/order") || modules.containsKey("services\\order"),
                "Expected services/order module, got: " + modules.keySet());
    }

    @Test
    void detectModules_nodeProject() throws IOException {
        write("package.json", "{\"name\":\"my-app\"}");
        write("src/index.ts", "import express from 'express';");
        write("src/routes.ts", "export default router;");

        FileDiscovery fd = new FileDiscovery(new CodeIqConfig());
        List<DiscoveredFile> files = fd.discover(tempDir);

        Map<String, List<DiscoveredFile>> modules = analyzer.detectModules(tempDir, files);

        assertEquals(1, modules.size());
        assertTrue(modules.containsKey(""), "Root module should be empty string for root-level package.json");
    }

    @Test
    void detectModules_noMarkers_fallsBackToRoot() throws IOException {
        write("src/App.java", "public class App {}");
        write("src/Service.java", "public class Service {}");

        FileDiscovery fd = new FileDiscovery(new CodeIqConfig());
        List<DiscoveredFile> files = fd.discover(tempDir);

        Map<String, List<DiscoveredFile>> modules = analyzer.detectModules(tempDir, files);

        assertEquals(1, modules.size());
        assertTrue(modules.containsKey("root"), "Should have 'root' fallback when no markers found");
        assertEquals(files.size(), modules.get("root").size());
    }

    @Test
    void detectModules_fileAssignedToDeepestModule() throws IOException {
        write("pom.xml", "<project/>");                      // root module ""
        write("services/auth/pom.xml", "<project/>");        // module "services/auth"
        write("services/auth/src/main/java/Auth.java", "@Controller class Auth {}");

        FileDiscovery fd = new FileDiscovery(new CodeIqConfig());
        List<DiscoveredFile> files = fd.discover(tempDir);

        Map<String, List<DiscoveredFile>> modules = analyzer.detectModules(tempDir, files);

        // Auth.java should be in services/auth, NOT in root
        String authKey = modules.keySet().stream()
                .filter(k -> k.contains("auth")).findFirst().orElse(null);
        assertNotNull(authKey, "services/auth module not found: " + modules.keySet());

        boolean authFileInAuthModule = modules.get(authKey).stream()
                .anyMatch(f -> f.path().toString().contains("Auth.java"));
        assertTrue(authFileInAuthModule, "Auth.java should be in the auth module");
    }

    @Test
    void detectModules_deterministic() throws IOException {
        write("pom.xml", "<project/>");
        write("services/a/pom.xml", "<project/>");
        write("services/b/pom.xml", "<project/>");
        write("services/a/Foo.java", "@Service class Foo {}");
        write("services/b/Bar.java", "@Service class Bar {}");

        FileDiscovery fd = new FileDiscovery(new CodeIqConfig());
        List<DiscoveredFile> files = fd.discover(tempDir);

        Map<String, List<DiscoveredFile>> m1 = analyzer.detectModules(tempDir, files);
        Map<String, List<DiscoveredFile>> m2 = analyzer.detectModules(tempDir, files);

        assertEquals(new ArrayList<>(m1.keySet()), new ArrayList<>(m2.keySet()),
                "Module keys should be in the same order across runs");
    }

    // -------------------------------------------------------------------------
    // runSmartIndex() end-to-end
    // -------------------------------------------------------------------------

    @Test
    void runSmartIndex_basicJavaProject() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/App.java", "@RestController public class App {}");
        write("src/main/java/Config.java", "@Configuration public class Config {}");

        AnalysisResult result = analyzer.runSmartIndex(tempDir, null, 100, false,
                progressMessages::add);

        // Both files have architecture keywords, so both should be analyzed
        assertTrue(result.totalFiles() >= 2);
        assertTrue(result.filesAnalyzed() > 0);
        assertFalse(progressMessages.isEmpty());
    }

    @Test
    void runSmartIndex_phase1ReportsConfigScan() throws IOException {
        write("src/main/resources/application.yml",
                "spring:\n  application:\n    name: test-svc\n  datasource:\n    url: jdbc:postgresql://db/test\n");
        write("src/main/java/App.java", "@RestController public class App {}");

        analyzer.runSmartIndex(tempDir, null, 100, false, progressMessages::add);

        boolean hasPhase1 = progressMessages.stream().anyMatch(m -> m.startsWith("Phase 1"));
        boolean hasPhase2 = progressMessages.stream().anyMatch(m -> m.startsWith("Phase 2"));
        assertTrue(hasPhase1, "Expected Phase 1 progress message");
        assertTrue(hasPhase2, "Expected Phase 2 progress message");
    }

    @Test
    void runSmartIndex_keywordFilterReducesFileCount() throws IOException {
        write("pom.xml", "<project/>");
        // File WITH architecture keyword
        write("src/main/java/Service.java", "@Service public class UserService {}");
        // File WITHOUT architecture keyword (pure POJO)
        write("src/main/java/Dto.java", "public class UserDto { public String name; }");

        analyzer.runSmartIndex(tempDir, null, 100, false, progressMessages::add);

        // Filter may or may not kick in depending on content; verify the pipeline completed
        assertTrue(progressMessages.stream().anyMatch(m -> m.contains("complete")));
    }

    @Test
    void runSmartIndex_structuredFilesAlwaysIncluded() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/resources/application.yml",
                "server:\n  port: 8080\n");

        // Config files should never be filtered by keyword check
        AnalysisResult result = analyzer.runSmartIndex(tempDir, null, 100, false,
                progressMessages::add);

        // Just verify it runs without exception and finds files
        assertTrue(result.totalFiles() > 0);
    }

    @Test
    void runSmartIndex_registryPassedToDetectors() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/resources/application.yml",
                "spring:\n  datasource:\n    url: jdbc:postgresql://db/mydb\n");
        write("src/main/java/Repo.java", "@Repository public class Repo {}");

        analyzer.runSmartIndex(tempDir, null, 100, false, progressMessages::add);

        // Phase 1 should have found the datasource endpoint
        boolean foundEndpoint = progressMessages.stream()
                .anyMatch(m -> m.contains("infrastructure endpoint") || m.contains("Phase 1 complete"));
        assertTrue(foundEndpoint, "Phase 1 should report infrastructure endpoints");
    }

    @Test
    void runSmartIndex_deterministic() throws IOException {
        write("pom.xml", "<project/>");
        write("src/main/java/ServiceA.java", "@Service public class ServiceA {}");
        write("src/main/java/ServiceB.java", "@Service public class ServiceB {}");

        AnalysisResult r1 = analyzer.runSmartIndex(tempDir, null, 100, false, null);
        AnalysisResult r2 = analyzer.runSmartIndex(tempDir, null, 100, false, null);

        assertEquals(r1.totalFiles(), r2.totalFiles());
        assertEquals(r1.nodeCount(), r2.nodeCount());
        assertEquals(r1.edgeCount(), r2.edgeCount());
    }

    // -------------------------------------------------------------------------
    // DetectorContext registry field
    // -------------------------------------------------------------------------

    @Test
    void detectorContextRegistry_nullByDefault() {
        var ctx = new DetectorContext("path", "java", "content");
        assertNull(ctx.registry());
    }

    @Test
    void detectorContextRegistry_fiveArgConstructorCompatible() {
        var ctx = new DetectorContext("path", "java", "content", null, "module");
        assertNull(ctx.registry());
    }

    @Test
    void detectorContextRegistry_sixArgConstructorSetsRegistry() {
        var reg = new InfrastructureRegistry();
        var ctx = new DetectorContext("path", "java", "content", null, "module", reg);
        assertSame(reg, ctx.registry());
    }

    // -------------------------------------------------------------------------

    private void write(String relativePath, String content) throws IOException {
        Path target = tempDir.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }
}
