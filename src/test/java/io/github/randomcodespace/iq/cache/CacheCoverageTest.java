package io.github.randomcodespace.iq.cache;

import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Additional coverage tests for the cache package — branches not hit by
 * existing tests.
 */
class CacheCoverageTest {

    // =====================================================================
    // FileHasher
    // =====================================================================
    @Nested
    class FileHasherCoverage {

        @Test
        void hashEmptyFile(@TempDir Path tempDir) throws IOException {
            Path empty = tempDir.resolve("empty.txt");
            Files.writeString(empty, "", StandardCharsets.UTF_8);

            String hash = FileHasher.hash(empty);
            assertNotNull(hash);
            assertEquals(32, hash.length());
            assertTrue(hash.matches("[0-9a-f]+"));
        }

        @Test
        void hashEmptyString() {
            String hash = FileHasher.hashString("");
            assertNotNull(hash);
            assertEquals(32, hash.length());
        }

        @Test
        void hashOfSameStringIsAlwaysSame() {
            String s = "deterministic content";
            assertEquals(FileHasher.hashString(s), FileHasher.hashString(s));
        }

        @Test
        void hashDiffersForUnicode() {
            String a = "hello";
            String b = "héllo";  // accented é
            assertNotEquals(FileHasher.hashString(a), FileHasher.hashString(b));
        }

        @Test
        void hashLargeContent() {
            // 1 MB string
            String large = "x".repeat(1_000_000);
            String hash = FileHasher.hashString(large);
            assertEquals(32, hash.length());
        }

        @Test
        void hashFileAndStringProduceSameResultForSameContent(@TempDir Path tempDir) throws IOException {
            String content = "match me";
            Path file = tempDir.resolve("match.txt");
            Files.writeString(file, content, StandardCharsets.UTF_8);

            String fileHash = FileHasher.hash(file);
            String stringHash = FileHasher.hashString(content);
            assertEquals(fileHash, stringHash);
        }
    }

    // =====================================================================
    // AnalysisCache — additional branches
    // =====================================================================
    @Nested
    class AnalysisCacheCoverage {

        private AnalysisCache cache;

        @BeforeEach
        void setUp(@TempDir Path tempDir) {
            cache = new AnalysisCache(tempDir.resolve("cov-test.db"));
        }

        @AfterEach
        void tearDown() {
            if (cache != null) cache.close();
        }

        @Test
        void storeAndLoadNodeWithAllProperties() {
            CodeNode node = new CodeNode("cls:X", NodeKind.CLASS, "X");
            node.setFqn("com.example.X");
            node.setFilePath("src/X.java");
            node.setModule("com.example");
            node.setLayer("backend");
            node.setLineStart(10);
            node.setLineEnd(50);
            node.setAnnotations(List.of("@Service", "@Transactional"));
            node.setProperties(Map.of("framework", "spring_boot", "layer", "backend"));

            cache.storeResults("hash-full", "src/X.java", "java", List.of(node), List.of());
            var result = cache.loadCachedResults("hash-full");

            assertNotNull(result);
            CodeNode loaded = result.nodes().getFirst();
            assertEquals("com.example.X", loaded.getFqn());
            assertEquals("com.example", loaded.getModule());
            assertEquals("backend", loaded.getLayer());
            assertEquals(10, loaded.getLineStart());
            assertEquals(50, loaded.getLineEnd());
            assertTrue(loaded.getAnnotations().contains("@Service"));
            assertEquals("spring_boot", loaded.getProperties().get("framework"));
        }

        @Test
        void storeEdgeAndLoadBackWithProperties() {
            CodeNode src = new CodeNode("cls:A", NodeKind.CLASS, "A");
            CodeNode tgt = new CodeNode("cls:B", NodeKind.CLASS, "B");
            CodeEdge edge = new CodeEdge("e:A->B", EdgeKind.DEPENDS_ON, "cls:A", tgt);
            edge.setProperties(Map.of("inferred", true, "reason", "naming_convention"));

            cache.storeResults("hash-edge", "src/A.java", "java",
                    List.of(src, tgt), List.of(edge));
            var result = cache.loadCachedResults("hash-edge");

            assertNotNull(result);
            assertEquals(1, result.edges().size());
            CodeEdge loaded = result.edges().getFirst();
            assertEquals(EdgeKind.DEPENDS_ON, loaded.getKind());
            assertEquals("cls:A", loaded.getSourceId());
            assertEquals("cls:B", loaded.getTarget().getId());
        }

        @Test
        void multipleStoresAndLoadsSeparateHashes() {
            var nodeA = new CodeNode("n:A", NodeKind.CLASS, "A");
            var nodeB = new CodeNode("n:B", NodeKind.CLASS, "B");

            cache.storeResults("hash-a", "A.java", "java", List.of(nodeA), List.of());
            cache.storeResults("hash-b", "B.java", "java", List.of(nodeB), List.of());

            var rA = cache.loadCachedResults("hash-a");
            var rB = cache.loadCachedResults("hash-b");

            assertNotNull(rA);
            assertNotNull(rB);
            assertEquals("n:A", rA.nodes().getFirst().getId());
            assertEquals("n:B", rB.nodes().getFirst().getId());
        }

        @Test
        void clearResetsAllCounters() {
            var n = new CodeNode("n:X", NodeKind.CLASS, "X");
            cache.storeResults("h1", "X.java", "java", List.of(n), List.of());
            cache.storeResults("h2", "Y.java", "java", List.of(n), List.of());
            cache.recordRun("sha1", 5);

            cache.clear();

            var stats = cache.getStats();
            assertEquals(0L, stats.get("cached_files"));
            assertEquals(0L, stats.get("cached_nodes"));
            assertEquals(0L, stats.get("total_runs"));
            assertNull(cache.getLastCommit());
        }

        @Test
        void multipleRunsGetLastCommitReturnsLatest() {
            cache.recordRun("sha-first", 1);
            cache.recordRun("sha-second", 2);
            cache.recordRun("sha-third", 3);

            assertEquals("sha-third", cache.getLastCommit());
        }

        @Test
        void isCachedReturnsTrueAfterStore() {
            var n = new CodeNode("n:A", NodeKind.CLASS, "A");
            cache.storeResults("tracked-hash", "A.java", "java", List.of(n), List.of());
            assertTrue(cache.isCached("tracked-hash"));
        }

        @Test
        void isCachedReturnsFalseForRemovedFile() {
            var n = new CodeNode("n:A", NodeKind.CLASS, "A");
            cache.storeResults("remove-hash", "A.java", "java", List.of(n), List.of());
            cache.removeFile("remove-hash");
            assertFalse(cache.isCached("remove-hash"));
        }

        @Test
        void storeEmptyNodeListIsCachedButLoadReturnsNull() {
            // Store with empty nodes and edges — isCached checks the files table
            cache.storeResults("empty-nodes-hash", "Empty.java", "java", List.of(), List.of());
            // The file entry is recorded
            assertTrue(cache.isCached("empty-nodes-hash"));
            // But loadCachedResults returns null when nodes AND edges are both empty
            var result = cache.loadCachedResults("empty-nodes-hash");
            assertNull(result);
        }

        @Test
        void statsTotalNodesAcrossMultipleFiles() {
            var n1 = new CodeNode("n:1", NodeKind.CLASS, "A");
            var n2 = new CodeNode("n:2", NodeKind.CLASS, "B");
            var n3 = new CodeNode("n:3", NodeKind.METHOD, "m");

            cache.storeResults("file1", "A.java", "java", List.of(n1, n2), List.of());
            cache.storeResults("file2", "B.java", "java", List.of(n3), List.of());

            var stats = cache.getStats();
            assertEquals(2L, stats.get("cached_files"));
            assertEquals(3L, stats.get("cached_nodes"));
        }

        @Test
        void deterministic() {
            var n = new CodeNode("n:D", NodeKind.CLASS, "D");
            cache.storeResults("det-hash", "D.java", "java", List.of(n), List.of());

            var r1 = cache.loadCachedResults("det-hash");
            var r2 = cache.loadCachedResults("det-hash");

            assertNotNull(r1);
            assertNotNull(r2);
            assertEquals(r1.nodes().size(), r2.nodes().size());
            assertEquals(r1.nodes().getFirst().getId(), r2.nodes().getFirst().getId());
        }
    }
}
