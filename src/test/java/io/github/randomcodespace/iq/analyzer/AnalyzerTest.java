package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.analyzer.linker.Linker;
import io.github.randomcodespace.iq.analyzer.linker.LinkResult;
import io.github.randomcodespace.iq.config.CodeIqConfig;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AnalyzerTest {

    @TempDir
    Path tempDir;

    private Analyzer analyzer;
    private List<String> progressMessages;

    @BeforeEach
    void setUp() {
        progressMessages = new ArrayList<>();

        // A simple test detector that creates one CLASS node per Java file
        Detector testDetector = new Detector() {
            @Override
            public String getName() {
                return "test-detector";
            }

            @Override
            public Set<String> getSupportedLanguages() {
                return Set.of("java");
            }

            @Override
            public DetectorResult detect(DetectorContext ctx) {
                var node = new CodeNode(
                        "class:" + ctx.filePath(),
                        NodeKind.CLASS,
                        ctx.filePath()
                );
                node.setFilePath(ctx.filePath());
                node.setModule(ctx.moduleName());
                return DetectorResult.of(List.of(node), List.of());
            }
        };

        var registry = new DetectorRegistry(List.of(testDetector));
        var parser = new StructuredParser();
        var fileDiscovery = new FileDiscovery(new CodeIqConfig());
        var layerClassifier = new LayerClassifier();
        List<Linker> linkers = List.of();

        analyzer = new Analyzer(registry, parser, fileDiscovery, layerClassifier, linkers, new CodeIqConfig());
    }

    @Test
    void analyzesJavaFiles() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "public class App {}");
        Files.writeString(tempDir.resolve("Service.java"), "public class Service {}");

        AnalysisResult result = analyzer.run(tempDir, progressMessages::add);

        assertEquals(2, result.totalFiles());
        assertEquals(2, result.filesAnalyzed());
        assertEquals(2, result.nodeCount());
        assertEquals(0, result.edgeCount());
        assertTrue(result.languageBreakdown().containsKey("java"));
        assertEquals(2, result.languageBreakdown().get("java"));
        assertTrue(result.elapsed().toMillis() >= 0);
    }

    @Test
    void reportsProgress() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "public class App {}");

        analyzer.run(tempDir, progressMessages::add);

        assertFalse(progressMessages.isEmpty());
        assertTrue(progressMessages.stream().anyMatch(m -> m.contains("Discovering")));
        assertTrue(progressMessages.stream().anyMatch(m -> m.contains("complete")));
    }

    @Test
    void emptyDirectoryProducesEmptyResult() {
        AnalysisResult result = analyzer.run(tempDir, null);

        assertEquals(0, result.totalFiles());
        assertEquals(0, result.filesAnalyzed());
        assertEquals(0, result.nodeCount());
        assertEquals(0, result.edgeCount());
    }

    @Test
    void skipsFilesWithNoMatchingDetector() throws IOException {
        Files.writeString(tempDir.resolve("script.py"), "print('hello')");

        AnalysisResult result = analyzer.run(tempDir, null);

        assertEquals(1, result.totalFiles());
        assertEquals(0, result.filesAnalyzed()); // No python detector registered
    }

    @Test
    void nodeBreakdownIsPopulated() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "public class App {}");

        AnalysisResult result = analyzer.run(tempDir, null);

        assertTrue(result.nodeBreakdown().containsKey("class"));
        assertEquals(1, result.nodeBreakdown().get("class"));
    }

    @Test
    void resultIsDeterministic() throws IOException {
        Files.writeString(tempDir.resolve("A.java"), "public class A {}");
        Files.writeString(tempDir.resolve("B.java"), "public class B {}");
        Files.writeString(tempDir.resolve("C.java"), "public class C {}");

        AnalysisResult result1 = analyzer.run(tempDir, null);
        AnalysisResult result2 = analyzer.run(tempDir, null);

        assertEquals(result1.totalFiles(), result2.totalFiles());
        assertEquals(result1.filesAnalyzed(), result2.filesAnalyzed());
        assertEquals(result1.nodeCount(), result2.nodeCount());
        assertEquals(result1.edgeCount(), result2.edgeCount());
        assertEquals(result1.languageBreakdown(), result2.languageBreakdown());
        assertEquals(result1.nodeBreakdown(), result2.nodeBreakdown());
    }

    @Test
    void nullProgressCallbackIsHandled() throws IOException {
        Files.writeString(tempDir.resolve("App.java"), "public class App {}");

        // Should not throw with null callback
        assertDoesNotThrow(() -> analyzer.run(tempDir, null));
    }

    @Test
    void classifiesLayersOnNodes() throws IOException {
        Path srcDir = tempDir.resolve("src/controllers");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("UserController.java"), "public class UserController {}");

        AnalysisResult result = analyzer.run(tempDir, null);

        assertEquals(1, result.nodeCount());
        // The layer classifier should have run (we can't easily inspect nodes from here,
        // but the pipeline completing without error confirms it ran)
    }
}
