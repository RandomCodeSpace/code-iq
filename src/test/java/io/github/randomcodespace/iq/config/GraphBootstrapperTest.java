package io.github.randomcodespace.iq.config;

import io.github.randomcodespace.iq.cache.AnalysisCache;
import io.github.randomcodespace.iq.graph.GraphStore;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class GraphBootstrapperTest {

    private GraphStore graphStore;
    private CodeIqConfig config;
    private GraphBootstrapper bootstrapper;

    @BeforeEach
    void setUp() {
        graphStore = mock(GraphStore.class);
        config = new CodeIqConfig();
        bootstrapper = new GraphBootstrapper(graphStore, config);
    }

    @Test
    void skipsBootstrapWhenNeo4jAlreadyHasData(@TempDir Path tempDir) {
        config.setRootPath(tempDir.toString());
        when(graphStore.count()).thenReturn(100L);

        bootstrapper.bootstrapNeo4jFromCache();

        verify(graphStore, never()).bulkSave(anyList());
    }

    @Test
    void skipsBootstrapWhenNoCacheFileExists(@TempDir Path tempDir) {
        config.setRootPath(tempDir.toString());
        when(graphStore.count()).thenReturn(0L);

        bootstrapper.bootstrapNeo4jFromCache();

        verify(graphStore, never()).bulkSave(anyList());
    }

    @Test
    void bootstrapsFromH2WhenNeo4jIsEmpty(@TempDir Path tempDir) throws IOException {
        config.setRootPath(tempDir.toString());
        when(graphStore.count()).thenReturn(0L);

        // Create a real H2 cache with test data
        Path cacheDir = tempDir.resolve(config.getCacheDir());
        Files.createDirectories(cacheDir);
        Path dbPath = cacheDir.resolve("analysis-cache.db");

        try (AnalysisCache cache = new AnalysisCache(dbPath)) {
            CodeNode node1 = new CodeNode("n1", NodeKind.CLASS, "MyClass");
            node1.setFilePath("src/MyClass.java");
            CodeNode node2 = new CodeNode("n2", NodeKind.METHOD, "myMethod");
            node2.setFilePath("src/MyClass.java");
            CodeEdge edge = new CodeEdge("e1", EdgeKind.CALLS, "n1",
                    new CodeNode("n2", NodeKind.METHOD, "myMethod"));
            cache.storeResults("hash1", "src/MyClass.java", "java",
                    List.of(node1, node2), List.of(edge));
        }

        bootstrapper.bootstrapNeo4jFromCache();

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<CodeNode>> captor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(graphStore).bulkSave(captor.capture());
        List<CodeNode> saved = new ArrayList<>(captor.getValue());
        assertEquals(2, saved.size());
    }

    @Test
    void skipsBootstrapWhenH2CacheIsEmpty(@TempDir Path tempDir) throws IOException {
        config.setRootPath(tempDir.toString());
        when(graphStore.count()).thenReturn(0L);

        // Create an empty H2 cache
        Path cacheDir = tempDir.resolve(config.getCacheDir());
        Files.createDirectories(cacheDir);
        Path dbPath = cacheDir.resolve("analysis-cache.db");

        try (AnalysisCache cache = new AnalysisCache(dbPath)) {
            // Empty cache - no data stored
        }

        bootstrapper.bootstrapNeo4jFromCache();

        verify(graphStore, never()).bulkSave(anyList());
    }

    @Test
    void attachesEdgesToSourceNodesBeforeSaving(@TempDir Path tempDir) throws IOException {
        config.setRootPath(tempDir.toString());
        when(graphStore.count()).thenReturn(0L);

        Path cacheDir = tempDir.resolve(config.getCacheDir());
        Files.createDirectories(cacheDir);
        Path dbPath = cacheDir.resolve("analysis-cache.db");

        try (AnalysisCache cache = new AnalysisCache(dbPath)) {
            CodeNode source = new CodeNode("src:node", NodeKind.CLASS, "Source");
            CodeNode target = new CodeNode("tgt:node", NodeKind.METHOD, "Target");
            CodeEdge edge = new CodeEdge("e1", EdgeKind.CALLS, "src:node", target);
            cache.storeResults("hash1", "src/file.java", "java",
                    List.of(source, target), List.of(edge));
        }

        bootstrapper.bootstrapNeo4jFromCache();

        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<CodeNode>> captor2 = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(graphStore).bulkSave(captor2.capture());
        List<CodeNode> saved = new ArrayList<>(captor2.getValue());

        // Find the source node and verify it has the edge attached
        boolean foundEdge = false;
        for (CodeNode n : saved) {
            if ("src:node".equals(n.getId())) {
                foundEdge = !n.getEdges().isEmpty();
            }
        }
        assertTrue(foundEdge, "Source node should have edges attached");
    }

    @Test
    void handlesExceptionGracefully(@TempDir Path tempDir) throws IOException {
        config.setRootPath(tempDir.toString());
        when(graphStore.count()).thenReturn(0L);

        // Create a corrupt "cache" file
        Path cacheDir = tempDir.resolve(config.getCacheDir());
        Files.createDirectories(cacheDir);
        Files.writeString(cacheDir.resolve("analysis-cache.mv.db"), "corrupt data");

        // Should not throw
        bootstrapper.bootstrapNeo4jFromCache();

        verify(graphStore, never()).bulkSave(anyList());
    }
}
