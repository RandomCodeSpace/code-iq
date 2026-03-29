package io.github.randomcodespace.iq.analyzer;

import io.github.randomcodespace.iq.analyzer.linker.Linker;
import io.github.randomcodespace.iq.cache.AnalysisCache;
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

/**
 * Tests that batched streaming indexing works correctly:
 * - Files are processed in batches
 * - Results are flushed to H2 after each batch
 * - Memory is bounded (no unbounded ArrayList growth)
 */
class BatchedStreamingTest {

    @TempDir
    Path tempDir;

    private Analyzer analyzer;

    @BeforeEach
    void setUp() {
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
        var config = new CodeIqConfig();
        var fileDiscovery = new FileDiscovery(config);
        var layerClassifier = new LayerClassifier();

        analyzer = new Analyzer(registry, parser, fileDiscovery, layerClassifier, List.of(), config);
    }

    @Test
    void batchedIndexWritesToH2() throws IOException {
        // Create test source files
        for (int i = 0; i < 10; i++) {
            Files.writeString(tempDir.resolve("TestClass" + i + ".java"),
                    "public class TestClass" + i + " {}");
        }

        // Run batched index with small batch size
        AnalysisResult result = analyzer.runBatchedIndex(tempDir, null, 3, false, null);

        // Should have found and analyzed 10 files
        assertEquals(10, result.totalFiles());
        assertEquals(10, result.filesAnalyzed());
        assertEquals(10, result.nodeCount());
        assertEquals(0, result.edgeCount());

        // Verify H2 has data
        Path cachePath = tempDir.resolve(".code-intelligence").resolve("analysis-cache.db");
        try (var cache = new AnalysisCache(cachePath)) {
            long nodeCount = cache.getNodeCount();
            assertEquals(10, nodeCount, "H2 should have 10 nodes");
        }
    }

    @Test
    void batchedIndexRespectsBatchSize() throws IOException {
        // Create enough files to span multiple batches
        for (int i = 0; i < 12; i++) {
            Files.writeString(tempDir.resolve("Class" + i + ".java"),
                    "public class Class" + i + " {}");
        }

        // Track progress messages to verify batching
        List<String> progressMessages = new ArrayList<>();
        AnalysisResult result = analyzer.runBatchedIndex(tempDir, null, 5, false,
                progressMessages::add);

        assertEquals(12, result.totalFiles());
        assertEquals(12, result.filesAnalyzed());

        // Should see multiple "Processing batch" messages
        // 12 files / 5 per batch = 3 batches (5, 5, 2)
        long batchMessages = progressMessages.stream()
                .filter(msg -> msg.startsWith("Processing batch"))
                .count();
        assertEquals(3, batchMessages, "Should have 3 batch progress messages");
    }

    @Test
    void batchedIndexIncrementalModeUsesCacheHits() throws IOException {
        Files.writeString(tempDir.resolve("Stable.java"), "public class Stable {}");

        // First run (populates cache)
        AnalysisResult result1 = analyzer.runBatchedIndex(tempDir, null, 100, true, null);
        assertEquals(1, result1.totalFiles());
        assertEquals(1, result1.filesAnalyzed());
        assertEquals(1, result1.nodeCount());

        // Second run (should use cache)
        List<String> messages = new ArrayList<>();
        AnalysisResult result2 = analyzer.runBatchedIndex(tempDir, null, 100, true,
                messages::add);

        // Should still produce the same counts (from cache)
        assertEquals(1, result2.totalFiles());
        assertEquals(1, result2.nodeCount());

        // Should report cache hits
        boolean hasCacheHit = messages.stream()
                .anyMatch(msg -> msg.contains("Cache hits"));
        assertTrue(hasCacheHit, "Should report cache hits on second run");
    }

    @Test
    void batchedIndexDeterministic() throws IOException {
        for (int i = 0; i < 8; i++) {
            Files.writeString(tempDir.resolve("Det" + i + ".java"),
                    "public class Det" + i + " { void run() {} }");
        }

        // Run twice with same settings
        AnalysisResult result1 = analyzer.runBatchedIndex(tempDir, null, 3, false, null);
        AnalysisResult result2 = analyzer.runBatchedIndex(tempDir, null, 3, false, null);

        assertEquals(result1.nodeCount(), result2.nodeCount(), "Node count must be deterministic");
        assertEquals(result1.edgeCount(), result2.edgeCount(), "Edge count must be deterministic");
        assertEquals(result1.totalFiles(), result2.totalFiles(), "File count must be deterministic");
    }

    @Test
    void batchedIndexWithSingleFileBatch() throws IOException {
        // Edge case: batch size of 1
        for (int i = 0; i < 3; i++) {
            Files.writeString(tempDir.resolve("Single" + i + ".java"),
                    "public class Single" + i + " {}");
        }

        List<String> messages = new ArrayList<>();
        AnalysisResult result = analyzer.runBatchedIndex(tempDir, null, 1, false,
                messages::add);

        assertEquals(3, result.totalFiles());
        assertEquals(3, result.filesAnalyzed());

        // Should see 3 batch messages (one per file)
        long batchMessages = messages.stream()
                .filter(msg -> msg.startsWith("Processing batch"))
                .count();
        assertEquals(3, batchMessages, "Should have 3 batches with batch-size=1");
    }
}
