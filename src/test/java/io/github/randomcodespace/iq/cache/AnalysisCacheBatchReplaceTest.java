package io.github.randomcodespace.iq.cache;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link AnalysisCache#replaceAll} and {@link AnalysisCache#storeBatchResults}
 * which are critical indexing pipeline methods with previously zero coverage.
 */
class AnalysisCacheBatchReplaceTest {

    private AnalysisCache cache;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        cache = new AnalysisCache(tempDir.resolve("test-cache.db"));
    }

    @AfterEach
    void tearDown() {
        if (cache != null) {
            cache.close();
        }
    }

    // --- storeBatchResults tests ---

    @Test
    void storeBatchResultsDelegatesCorrectly() {
        CodeNode node = new CodeNode("batch:n1", NodeKind.CLASS, "BatchClass");
        node.setFilePath("src/Batch.java");
        CodeEdge edge = new CodeEdge("batch:e1", EdgeKind.CALLS, "batch:n1",
                new CodeNode("batch:n2", NodeKind.METHOD, "batchMethod"));

        cache.storeBatchResults("batch-001", "src/Batch.java", "java",
                List.of(node), List.of(edge));

        assertTrue(cache.isCached("batch-001"));
        var result = cache.loadCachedResults("batch-001");
        assertNotNull(result);
        assertEquals(1, result.nodes().size());
        assertEquals(1, result.edges().size());
        assertEquals("batch:n1", result.nodes().getFirst().getId());
    }

    @Test
    void storeBatchResultsWithEmptyLists() {
        cache.storeBatchResults("empty-batch", "src/empty.java", "java",
                List.of(), List.of());

        assertTrue(cache.isCached("empty-batch"));
        // Empty nodes/edges return null from loadCachedResults
        assertNull(cache.loadCachedResults("empty-batch"));
    }

    @Test
    void storeBatchResultsPreservesNodeProperties() {
        CodeNode node = new CodeNode("batch:props", NodeKind.ENDPOINT, "GET /api/test");
        node.setFilePath("src/Controller.java");
        node.setLayer("backend");
        node.setModule("api");
        node.setFqn("com.example.Controller.getTest");
        node.setLineStart(10);
        node.setLineEnd(20);
        node.setAnnotations(List.of("@GetMapping", "@ResponseBody"));
        node.setProperties(Map.of("method", "GET", "path", "/api/test", "framework", "spring_boot"));

        cache.storeBatchResults("batch-props", "src/Controller.java", "java",
                List.of(node), List.of());

        var result = cache.loadCachedResults("batch-props");
        assertNotNull(result);
        CodeNode loaded = result.nodes().getFirst();
        assertEquals("batch:props", loaded.getId());
        assertEquals(NodeKind.ENDPOINT, loaded.getKind());
        assertEquals("GET /api/test", loaded.getLabel());
        assertEquals("src/Controller.java", loaded.getFilePath());
        assertEquals("backend", loaded.getLayer());
        assertEquals("api", loaded.getModule());
        assertEquals("com.example.Controller.getTest", loaded.getFqn());
        assertEquals(10, loaded.getLineStart());
        assertEquals(20, loaded.getLineEnd());
        assertEquals(List.of("@GetMapping", "@ResponseBody"), loaded.getAnnotations());
        assertEquals("GET", loaded.getProperties().get("method"));
        assertEquals("spring_boot", loaded.getProperties().get("framework"));
    }

    // --- replaceAll tests ---

    @Test
    void replaceAllClearsPreviousDataAndStoresNew() {
        // Store initial data
        CodeNode original = new CodeNode("orig:n1", NodeKind.CLASS, "Original");
        cache.storeResults("hash1", "src/orig.java", "java", List.of(original), List.of());
        assertEquals(1, cache.getNodeCount());

        // Replace with enriched data
        CodeNode enriched1 = new CodeNode("enr:n1", NodeKind.CLASS, "EnrichedClass");
        enriched1.setLayer("backend");
        CodeNode enriched2 = new CodeNode("enr:n2", NodeKind.SERVICE, "MyService");
        enriched2.setLayer("backend");
        CodeEdge enrichedEdge = new CodeEdge("enr:e1", EdgeKind.CONTAINS, "enr:n2", enriched1);

        cache.replaceAll(List.of(enriched1, enriched2), List.of(enrichedEdge));

        // Original data should be gone
        assertFalse(cache.isCached("hash1"));

        // New enriched data should be present under __enriched__ hash
        assertTrue(cache.isCached("__enriched__"));
        assertEquals(2, cache.getNodeCount());
        assertEquals(1, cache.getEdgeCount());
    }

    @Test
    void replaceAllWithEmptyListsClearsEverything() {
        // Store initial data
        CodeNode node = new CodeNode("n1", NodeKind.CLASS, "C1");
        cache.storeResults("hash1", "f1.java", "java", List.of(node), List.of());
        assertEquals(1, cache.getNodeCount());

        cache.replaceAll(List.of(), List.of());

        assertEquals(0, cache.getNodeCount());
        assertEquals(0, cache.getEdgeCount());
    }

    @Test
    void replaceAllPreservesAnalysisRunMetadata() {
        cache.recordRun("commit-sha-1", 100);
        cache.recordRun("commit-sha-2", 200);

        CodeNode node = new CodeNode("n1", NodeKind.CLASS, "C1");
        cache.storeResults("hash1", "f1.java", "java", List.of(node), List.of());

        cache.replaceAll(List.of(node), List.of());

        // Analysis run metadata should survive the replace
        assertEquals("commit-sha-2", cache.getLastCommit());
    }

    @Test
    void replaceAllWithLargeDataset() {
        List<CodeNode> nodes = new ArrayList<>();
        List<CodeEdge> edges = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            CodeNode n = new CodeNode("node:" + i, NodeKind.CLASS, "Class" + i);
            n.setFilePath("src/Class" + i + ".java");
            n.setLayer("backend");
            nodes.add(n);
        }
        for (int i = 0; i < 499; i++) {
            edges.add(new CodeEdge("edge:" + i, EdgeKind.CALLS, "node:" + i,
                    new CodeNode("node:" + (i + 1), NodeKind.CLASS, "Class" + (i + 1))));
        }

        cache.replaceAll(nodes, edges);

        assertEquals(500, cache.getNodeCount());
        assertEquals(499, cache.getEdgeCount());
    }

    @Test
    void replaceAllDataCanBeLoadedBack() {
        CodeNode node = new CodeNode("enr:n1", NodeKind.ENDPOINT, "GET /health");
        node.setLayer("backend");
        node.setProperties(Map.of("method", "GET"));
        CodeEdge edge = new CodeEdge("enr:e1", EdgeKind.DEFINES, "enr:n1",
                new CodeNode("enr:n2", NodeKind.METHOD, "health"));

        cache.replaceAll(List.of(node), List.of(edge));

        List<CodeNode> allNodes = cache.loadAllNodes();
        List<CodeEdge> allEdges = cache.loadAllEdges();
        assertEquals(1, allNodes.size());
        assertEquals(1, allEdges.size());
        assertEquals("enr:n1", allNodes.getFirst().getId());
        assertEquals("backend", allNodes.getFirst().getLayer());
    }

    // --- Concurrent access test ---

    @Test
    void concurrentStoreAndReadDoesNotCorrupt() throws InterruptedException {
        // Store some initial data
        for (int i = 0; i < 10; i++) {
            CodeNode n = new CodeNode("n:" + i, NodeKind.CLASS, "C" + i);
            cache.storeResults("hash:" + i, "f" + i + ".java", "java",
                    List.of(n), List.of());
        }

        // Run concurrent reads and writes
        Thread writer = new Thread(() -> {
            for (int i = 10; i < 20; i++) {
                CodeNode n = new CodeNode("n:" + i, NodeKind.CLASS, "C" + i);
                cache.storeResults("hash:" + i, "f" + i + ".java", "java",
                        List.of(n), List.of());
            }
        });

        Thread reader = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                cache.loadCachedResults("hash:" + i);
                cache.getNodeCount();
            }
        });

        writer.start();
        reader.start();
        writer.join(5000);
        reader.join(5000);

        // Should not have corrupted data
        assertTrue(cache.getNodeCount() >= 10);
    }
}
