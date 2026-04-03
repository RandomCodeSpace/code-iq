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

import static org.assertj.core.api.Assertions.assertThat;

class LanguageEnricherTest {

    @TempDir
    Path tempDir;

    @Test
    void enrich_noExtractors_noEdgesAdded() {
        LanguageEnricher enricher = new LanguageEnricher(List.of());
        List<CodeNode> nodes = List.of(node("id1", NodeKind.METHOD, "fn", "src/Foo.java"));
        List<CodeEdge> edges = new ArrayList<>();

        enricher.enrich(nodes, edges, tempDir);

        assertThat(edges).isEmpty();
    }

    @Test
    void enrich_runsPipelineAndAddsEdges() throws IOException {
        // Create a real source file
        Path javaFile = tempDir.resolve("Foo.java");
        Files.writeString(javaFile, """
                public class Foo {
                    public void caller() { callee(); }
                    public void callee() {}
                }
                """, StandardCharsets.UTF_8);

        CodeNode caller = node("method:Foo:caller", NodeKind.METHOD, "caller", "Foo.java");
        CodeNode callee = node("method:Foo:callee", NodeKind.METHOD, "callee", "Foo.java");
        List<CodeNode> nodes = List.of(caller, callee);
        List<CodeEdge> edges = new ArrayList<>();

        // Stub extractor that always returns a CALLS edge
        LanguageExtractor stubExtractor = new LanguageExtractor() {
            @Override
            public String getLanguage() { return "java"; }

            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode node) {
                if (!"caller".equals(node.getLabel())) return LanguageExtractionResult.empty();
                CodeEdge edge = new CodeEdge("calls:stub", EdgeKind.CALLS, caller.getId(), callee);
                return new LanguageExtractionResult(List.of(edge), List.of(), Map.of(),
                        CapabilityLevel.EXACT);
            }
        };

        LanguageEnricher enricher = new LanguageEnricher(List.of(stubExtractor));
        enricher.enrich(nodes, edges, tempDir);

        assertThat(edges).hasSize(1);
        assertThat(edges.get(0).getKind()).isEqualTo(EdgeKind.CALLS);
    }

    @Test
    void enrich_typeHints_addedToNodeProperties() throws IOException {
        Path pyFile = tempDir.resolve("service.py");
        Files.writeString(pyFile, "def compute(x: int) -> str:\n    pass\n", StandardCharsets.UTF_8);

        CodeNode fnNode = node("py:service.py:fn:compute", NodeKind.METHOD, "compute", "service.py");
        List<CodeNode> nodes = List.of(fnNode);
        List<CodeEdge> edges = new ArrayList<>();

        LanguageExtractor stubExtractor = new LanguageExtractor() {
            @Override
            public String getLanguage() { return "python"; }

            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode n) {
                return new LanguageExtractionResult(List.of(), List.of(),
                        Map.of("param_types", "x:int", "return_type", "str"),
                        CapabilityLevel.PARTIAL);
            }
        };

        LanguageEnricher enricher = new LanguageEnricher(List.of(stubExtractor));
        enricher.enrich(nodes, edges, tempDir);

        assertThat(fnNode.getProperties()).containsEntry("param_types", "x:int");
        assertThat(fnNode.getProperties()).containsEntry("return_type", "str");
    }

    @Test
    void enrich_extractorThrows_pipelineContinues() throws IOException {
        Path javaFile = tempDir.resolve("Bad.java");
        Files.writeString(javaFile, "class Bad {}", StandardCharsets.UTF_8);

        CodeNode node1 = node("n1", NodeKind.METHOD, "m1", "Bad.java");
        CodeNode node2 = node("n2", NodeKind.METHOD, "m2", "Bad.java");
        List<CodeNode> nodes = List.of(node1, node2);
        List<CodeEdge> edges = new ArrayList<>();

        LanguageExtractor faultyExtractor = new LanguageExtractor() {
            @Override
            public String getLanguage() { return "java"; }

            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode node) {
                if ("m1".equals(node.getLabel())) {
                    throw new RuntimeException("Simulated extractor failure");
                }
                CodeEdge e = new CodeEdge("ok-edge", EdgeKind.CALLS, node.getId(), node1);
                return new LanguageExtractionResult(List.of(e), List.of(), Map.of(),
                        CapabilityLevel.PARTIAL);
            }
        };

        LanguageEnricher enricher = new LanguageEnricher(List.of(faultyExtractor));
        // Should not throw
        enricher.enrich(nodes, edges, tempDir);

        // node2 should still produce an edge even though node1 failed
        assertThat(edges).hasSize(1);
    }

    @Test
    void enrich_javascriptAlias_routedToTypescriptExtractor() throws IOException {
        Path jsFile = tempDir.resolve("app.js");
        Files.writeString(jsFile, "function run() {}", StandardCharsets.UTF_8);

        CodeNode node = node("js:app.js:fn:run", NodeKind.METHOD, "run", "app.js");
        List<CodeNode> nodes = List.of(node);
        List<CodeEdge> edges = new ArrayList<>();

        List<String> calledFor = new ArrayList<>();
        LanguageExtractor tsExtractor = new LanguageExtractor() {
            @Override
            public String getLanguage() { return "typescript"; }

            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode n) {
                calledFor.add(ctx.language());
                return LanguageExtractionResult.empty();
            }
        };

        LanguageEnricher enricher = new LanguageEnricher(List.of(tsExtractor));
        enricher.enrich(nodes, edges, tempDir);

        // The typescript extractor should have been called for the .js file
        assertThat(calledFor).hasSize(1);
        assertThat(calledFor.get(0)).isEqualTo("javascript");
    }

    @Test
    void enrich_nodesByFile_processedInDeterministicOrder() throws IOException {
        Path aFile = tempDir.resolve("a.java");
        Path bFile = tempDir.resolve("b.java");
        Files.writeString(aFile, "class A {}", StandardCharsets.UTF_8);
        Files.writeString(bFile, "class B {}", StandardCharsets.UTF_8);

        CodeNode nodeA = node("n:a.java:class:A", NodeKind.CLASS, "A", "a.java");
        CodeNode nodeB = node("n:b.java:class:B", NodeKind.CLASS, "B", "b.java");

        List<String> run1Order = new ArrayList<>();
        List<String> run2Order = new ArrayList<>();

        LanguageEnricher enricher1 = new LanguageEnricher(List.of(new LanguageExtractor() {
            @Override public String getLanguage() { return "java"; }
            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode n) {
                run1Order.add(ctx.filePath());
                return LanguageExtractionResult.empty();
            }
        }));

        LanguageEnricher enricher2 = new LanguageEnricher(List.of(new LanguageExtractor() {
            @Override public String getLanguage() { return "java"; }
            @Override
            public LanguageExtractionResult extract(DetectorContext ctx, CodeNode n) {
                run2Order.add(ctx.filePath());
                return LanguageExtractionResult.empty();
            }
        }));

        // Input node order differs between runs; file iteration must be alphabetical in both.
        enricher1.enrich(List.of(nodeA, nodeB), new ArrayList<>(), tempDir);
        enricher2.enrich(List.of(nodeB, nodeA), new ArrayList<>(), tempDir);

        assertThat(run1Order).isEqualTo(run2Order);
        assertThat(run1Order).containsExactly("a.java", "b.java");
    }

    @Test
    void detectLanguage_mapsExtensionsCorrectly() {
        assertThat(LanguageEnricher.detectLanguage("Foo.java")).isEqualTo("java");
        assertThat(LanguageEnricher.detectLanguage("app.ts")).isEqualTo("typescript");
        assertThat(LanguageEnricher.detectLanguage("app.tsx")).isEqualTo("typescript");
        assertThat(LanguageEnricher.detectLanguage("app.js")).isEqualTo("javascript");
        assertThat(LanguageEnricher.detectLanguage("app.jsx")).isEqualTo("javascript");
        assertThat(LanguageEnricher.detectLanguage("service.py")).isEqualTo("python");
        assertThat(LanguageEnricher.detectLanguage("main.go")).isEqualTo("go");
        assertThat(LanguageEnricher.detectLanguage("app.yaml")).isNull();
        assertThat(LanguageEnricher.detectLanguage(null)).isNull();
    }

    private static CodeNode node(String id, NodeKind kind, String label, String filePath) {
        CodeNode n = new CodeNode(id, kind, label);
        n.setFqn(id);
        n.setFilePath(filePath);
        return n;
    }
}
