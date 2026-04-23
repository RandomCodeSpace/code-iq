package io.github.randomcodespace.iq.intelligence.extractor;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extended tests for {@link LanguageEnricher} covering branches not reached by
 * the primary test suite: file_type filters, unmatched extractors, no-op paths,
 * unreadable files, minified skips, null filePath on nodes, and FQN-keyed
 * registry entries.
 */
class LanguageEnricherExtendedTest {

    @TempDir
    Path tempDir;

    // ---------------------------------------------------------------
    // Empty tasks: no-extractor path (existing) vs no-matching-language path
    // ---------------------------------------------------------------

    @Test
    void enrich_noMatchingLanguage_noFailNoEdges() throws IOException {
        // Extractor exists but no node's language matches.
        Files.writeString(tempDir.resolve("config.yaml"), "key: value",
                StandardCharsets.UTF_8);

        CodeNode node = node("y:config.yaml:key:x", NodeKind.MODULE, "x", "config.yaml");
        List<CodeEdge> edges = new ArrayList<>();

        AtomicInteger calls = new AtomicInteger();
        LanguageExtractor javaExt = new LanguageExtractor() {
            @Override public String getLanguage() { return "java"; }
            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode n) {
                calls.incrementAndGet();
                return LanguageExtractionResult.empty();
            }
        };

        new LanguageEnricher(List.of(javaExt)).enrich(List.of(node), edges, tempDir);
        assertThat(calls.get()).isZero();
        assertThat(edges).isEmpty();
    }

    // ---------------------------------------------------------------
    // file_type skips: test / generated / minified / binary / text / filtered
    // ---------------------------------------------------------------

    @Test
    void enrich_skipsNodesWithFileTypeTest() throws IOException {
        Files.writeString(tempDir.resolve("FooTest.java"),
                "class FooTest {}", StandardCharsets.UTF_8);
        CodeNode n = node("n1", NodeKind.CLASS, "FooTest", "FooTest.java");
        n.getProperties().put("file_type", "test");

        AtomicInteger calls = new AtomicInteger();
        LanguageExtractor ext = new LanguageExtractor() {
            @Override public String getLanguage() { return "java"; }
            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode nn) {
                calls.incrementAndGet();
                return LanguageExtractionResult.empty();
            }
        };
        new LanguageEnricher(List.of(ext)).enrich(List.of(n), new ArrayList<>(), tempDir);
        assertThat(calls.get()).as("test-type nodes must be skipped").isZero();
    }

    @Test
    void enrich_skipsNodesWithFileTypeGenerated() throws IOException {
        Files.writeString(tempDir.resolve("Gen.java"),
                "class Gen {}", StandardCharsets.UTF_8);
        CodeNode n = node("n1", NodeKind.CLASS, "Gen", "Gen.java");
        n.getProperties().put("file_type", "generated");

        AtomicInteger calls = new AtomicInteger();
        LanguageExtractor ext = new LanguageExtractor() {
            @Override public String getLanguage() { return "java"; }
            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode nn) {
                calls.incrementAndGet();
                return LanguageExtractionResult.empty();
            }
        };
        new LanguageEnricher(List.of(ext)).enrich(List.of(n), new ArrayList<>(), tempDir);
        assertThat(calls.get()).as("generated-type nodes must be skipped").isZero();
    }

    @Test
    void enrich_skipsNodesWithFileTypeMinifiedBinaryTextFiltered() throws IOException {
        Files.writeString(tempDir.resolve("Mixed.java"),
                "class Mixed {}", StandardCharsets.UTF_8);

        AtomicInteger calls = new AtomicInteger();
        LanguageExtractor ext = new LanguageExtractor() {
            @Override public String getLanguage() { return "java"; }
            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode nn) {
                calls.incrementAndGet();
                return LanguageExtractionResult.empty();
            }
        };

        for (String ft : new String[]{"minified", "binary", "text", "filtered"}) {
            calls.set(0);
            CodeNode n = node("n:" + ft, NodeKind.CLASS, "Mixed", "Mixed.java");
            n.getProperties().put("file_type", ft);
            new LanguageEnricher(List.of(ext)).enrich(List.of(n), new ArrayList<>(), tempDir);
            assertThat(calls.get())
                    .as("file_type='%s' must cause skip", ft)
                    .isZero();
        }
    }

    @Test
    void enrich_fileTypeSourceIsProcessed() throws IOException {
        // The counter-positive of the skip list: any file_type not in the
        // skip-set (e.g. "source") must still be processed.
        Files.writeString(tempDir.resolve("Src.java"),
                "class Src {}", StandardCharsets.UTF_8);
        CodeNode n = node("n", NodeKind.CLASS, "Src", "Src.java");
        n.getProperties().put("file_type", "source");

        AtomicInteger calls = new AtomicInteger();
        LanguageExtractor ext = new LanguageExtractor() {
            @Override public String getLanguage() { return "java"; }
            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode nn) {
                calls.incrementAndGet();
                return LanguageExtractionResult.empty();
            }
        };
        new LanguageEnricher(List.of(ext)).enrich(List.of(n), new ArrayList<>(), tempDir);
        assertThat(calls.get()).isOne();
    }

    @Test
    void enrich_nullFileTypeIsProcessed() throws IOException {
        Files.writeString(tempDir.resolve("NoType.java"),
                "class NoType {}", StandardCharsets.UTF_8);
        CodeNode n = node("n", NodeKind.CLASS, "NoType", "NoType.java");
        // no file_type property set

        AtomicInteger calls = new AtomicInteger();
        LanguageExtractor ext = new LanguageExtractor() {
            @Override public String getLanguage() { return "java"; }
            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode nn) {
                calls.incrementAndGet();
                return LanguageExtractionResult.empty();
            }
        };
        new LanguageEnricher(List.of(ext)).enrich(List.of(n), new ArrayList<>(), tempDir);
        assertThat(calls.get()).isOne();
    }

    // ---------------------------------------------------------------
    // File read failures: missing file must not fail the pipeline
    // ---------------------------------------------------------------

    @Test
    void enrich_missingFile_extractorNotCalled() {
        // No file created in tempDir — readFile() returns null → task returns null
        CodeNode n = node("n", NodeKind.CLASS, "Missing", "Missing.java");

        AtomicInteger calls = new AtomicInteger();
        LanguageExtractor ext = new LanguageExtractor() {
            @Override public String getLanguage() { return "java"; }
            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode nn) {
                calls.incrementAndGet();
                return LanguageExtractionResult.empty();
            }
        };
        List<CodeEdge> edges = new ArrayList<>();
        new LanguageEnricher(List.of(ext)).enrich(List.of(n), edges, tempDir);
        assertThat(calls.get()).isZero();
        assertThat(edges).isEmpty();
    }

    // ---------------------------------------------------------------
    // Nodes with null filePath: skipped, never grouped into tasks
    // ---------------------------------------------------------------

    @Test
    void enrich_nullFilePathNode_ignored() {
        CodeNode n = new CodeNode("n", NodeKind.CLASS, "NoPath");
        n.setFqn("NoPath");
        // filePath deliberately null

        AtomicInteger calls = new AtomicInteger();
        LanguageExtractor ext = new LanguageExtractor() {
            @Override public String getLanguage() { return "java"; }
            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode nn) {
                calls.incrementAndGet();
                return LanguageExtractionResult.empty();
            }
        };
        new LanguageEnricher(List.of(ext)).enrich(List.of(n), new ArrayList<>(), tempDir);
        assertThat(calls.get()).isZero();
    }

    // ---------------------------------------------------------------
    // Minified file: large + long-line .js file is skipped
    // ---------------------------------------------------------------

    @Test
    void enrich_minifiedLargeJsFile_skipped() throws IOException {
        // Create ~60KB single-line .js — triggers minified heuristic
        // (>50KB, js/css extension, avg line length > 1000)
        StringBuilder sb = new StringBuilder(60_000);
        for (int i = 0; i < 60_000; i++) sb.append('x');
        // Put everything on a single line (no newlines) to guarantee ratio > 1000
        Files.writeString(tempDir.resolve("bundle.js"), sb.toString(),
                StandardCharsets.UTF_8);

        CodeNode n = node("n", NodeKind.MODULE, "bundle", "bundle.js");

        AtomicInteger calls = new AtomicInteger();
        LanguageExtractor ext = new LanguageExtractor() {
            @Override public String getLanguage() { return "typescript"; }
            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode nn) {
                calls.incrementAndGet();
                return LanguageExtractionResult.empty();
            }
        };
        new LanguageEnricher(List.of(ext)).enrich(List.of(n), new ArrayList<>(), tempDir);
        assertThat(calls.get()).as("minified .js must be skipped").isZero();
    }

    @Test
    void enrich_smallFile_notConsideredMinified() throws IOException {
        // Same extension but well under the 50KB minified threshold
        Files.writeString(tempDir.resolve("small.js"),
                "function f() {}\n", StandardCharsets.UTF_8);
        CodeNode n = node("n", NodeKind.METHOD, "f", "small.js");

        AtomicInteger calls = new AtomicInteger();
        LanguageExtractor ext = new LanguageExtractor() {
            @Override public String getLanguage() { return "typescript"; }
            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode nn) {
                calls.incrementAndGet();
                return LanguageExtractionResult.empty();
            }
        };
        new LanguageEnricher(List.of(ext)).enrich(List.of(n), new ArrayList<>(), tempDir);
        assertThat(calls.get()).isOne();
    }

    // ---------------------------------------------------------------
    // buildRegistry: nodes registered under both id and fqn
    // ---------------------------------------------------------------

    @Test
    void enrich_registryExposesNodesByIdAndFqn() throws IOException {
        Files.writeString(tempDir.resolve("Foo.java"),
                "class Foo {}", StandardCharsets.UTF_8);

        CodeNode n = node("method:Foo:bar", NodeKind.METHOD, "bar", "Foo.java");
        n.setFqn("com.acme.Foo.bar");

        LanguageExtractor ext = new LanguageExtractor() {
            @Override public String getLanguage() { return "java"; }
            @Override
            @SuppressWarnings("unchecked")
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode node) {
                Map<String, CodeNode> registry = (Map<String, CodeNode>) ctx.parsedData();
                assertThat(registry).isNotNull();
                // Nodes must be registered by both id and fqn
                assertThat(registry).containsKey("method:Foo:bar");
                assertThat(registry).containsKey("com.acme.Foo.bar");
                return LanguageExtractionResult.empty();
            }
        };
        new LanguageEnricher(List.of(ext)).enrich(List.of(n), new ArrayList<>(), tempDir);
    }

    @Test
    void enrich_registrySkipsBlankFqn() throws IOException {
        // blank fqn must NOT be inserted as a registry key
        Files.writeString(tempDir.resolve("X.java"),
                "class X {}", StandardCharsets.UTF_8);
        CodeNode n = node("id1", NodeKind.CLASS, "X", "X.java");
        n.setFqn("");

        LanguageExtractor ext = new LanguageExtractor() {
            @Override public String getLanguage() { return "java"; }
            @Override
            @SuppressWarnings("unchecked")
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode node) {
                Map<String, CodeNode> registry = (Map<String, CodeNode>) ctx.parsedData();
                assertThat(registry).containsKey("id1");
                assertThat(registry).doesNotContainKey("");
                return LanguageExtractionResult.empty();
            }
        };
        new LanguageEnricher(List.of(ext)).enrich(List.of(n), new ArrayList<>(), tempDir);
    }

    // ---------------------------------------------------------------
    // Symbol references aggregated alongside call edges
    // ---------------------------------------------------------------

    @Test
    void enrich_aggregatesSymbolReferencesAndCallEdges() throws IOException {
        Files.writeString(tempDir.resolve("A.java"),
                "class A {}", StandardCharsets.UTF_8);
        CodeNode a = node("a", NodeKind.CLASS, "A", "A.java");

        CodeEdge call = new CodeEdge("call:1", EdgeKind.CALLS, "a", a);
        CodeEdge sym  = new CodeEdge("sym:1", EdgeKind.DEFINES, "a", a);
        LanguageExtractor ext = new LanguageExtractor() {
            @Override public String getLanguage() { return "java"; }
            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode n) {
                return new LanguageExtractionResult(
                        List.of(call), List.of(sym), Map.of(), CapabilityLevel.PARTIAL);
            }
        };
        List<CodeEdge> edges = new ArrayList<>();
        new LanguageEnricher(List.of(ext)).enrich(List.of(a), edges, tempDir);
        // Both categories must be merged into the edge list
        assertThat(edges).hasSize(2);
        assertThat(edges).extracting(CodeEdge::getKind)
                .containsExactlyInAnyOrder(EdgeKind.CALLS, EdgeKind.DEFINES);
    }

    // ---------------------------------------------------------------
    // detectLanguage: additional extensions not covered elsewhere
    // ---------------------------------------------------------------

    @Test
    void detectLanguage_handlesMjsCjsAndCaseAndMissingDot() {
        assertThat(LanguageEnricher.detectLanguage("bundle.mjs")).isEqualTo("javascript");
        assertThat(LanguageEnricher.detectLanguage("bundle.cjs")).isEqualTo("javascript");
        // Upper-case extensions are folded to lower-case
        assertThat(LanguageEnricher.detectLanguage("APP.JS")).isEqualTo("javascript");
        assertThat(LanguageEnricher.detectLanguage("Foo.PY")).isEqualTo("python");
        assertThat(LanguageEnricher.detectLanguage("Main.GO")).isEqualTo("go");
        // pyw is a valid python extension
        assertThat(LanguageEnricher.detectLanguage("win.pyw")).isEqualTo("python");
        // No extension → null
        assertThat(LanguageEnricher.detectLanguage("Dockerfile")).isNull();
        assertThat(LanguageEnricher.detectLanguage("")).isNull();
    }

    // ---------------------------------------------------------------
    // Helper
    // ---------------------------------------------------------------

    private static CodeNode node(String id, NodeKind kind, String label, String filePath) {
        CodeNode n = new CodeNode(id, kind, label);
        n.setFqn(id);
        n.setFilePath(filePath);
        return n;
    }
}
