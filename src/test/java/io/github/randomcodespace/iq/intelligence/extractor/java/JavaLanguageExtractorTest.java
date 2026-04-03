package io.github.randomcodespace.iq.intelligence.extractor.java;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.extractor.LanguageExtractionResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JavaLanguageExtractorTest {

    private final JavaLanguageExtractor extractor = new JavaLanguageExtractor();

    @Test
    void getLanguage_returnsJava() {
        assertThat(extractor.getLanguage()).isEqualTo("java");
    }

    @Test
    void extract_methodNode_detectsCallEdge() {
        CodeNode caller = node("method:Foo:doWork", NodeKind.METHOD, "doWork");
        CodeNode callee = node("method:Bar:helper", NodeKind.METHOD, "helper");

        Map<String, CodeNode> registry = new HashMap<>();
        registry.put(callee.getId(), callee);
        registry.put(callee.getLabel(), callee);

        String content = """
                public class Foo {
                    public void doWork() {
                        helper();
                    }
                }
                """;

        DetectorContext ctx = new DetectorContext("Foo.java", "java", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, caller);

        assertThat(result.callEdges()).hasSize(1);
        assertThat(result.callEdges().get(0).getKind()).isEqualTo(EdgeKind.CALLS);
        assertThat(result.callEdges().get(0).getSourceId()).isEqualTo(caller.getId());
        assertThat(result.callEdges().get(0).getTarget().getId()).isEqualTo(callee.getId());
    }

    @Test
    void extract_classNode_extractsTypeHierarchy() {
        CodeNode classNode = node("class:Foo", NodeKind.CLASS, "Foo");

        String content = """
                public class Foo extends BaseService implements Serializable, Runnable {
                }
                """;

        DetectorContext ctx = new DetectorContext("Foo.java", "java", content, Map.of(), null);
        LanguageExtractionResult result = extractor.extract(ctx, classNode);

        assertThat(result.typeHints()).containsEntry("extends_type", "BaseService");
        assertThat(result.typeHints()).containsKey("implements_types");
        assertThat(result.typeHints().get("implements_types")).contains("Serializable").contains("Runnable");
    }

    @Test
    void extract_wrongLanguageNode_returnsEmpty() {
        CodeNode node = node("config:app.yaml", NodeKind.CONFIG_FILE, "app");
        DetectorContext ctx = new DetectorContext("app.yaml", "yaml", "key: value", Map.of(), null);
        LanguageExtractionResult result = extractor.extract(ctx, node);

        assertThat(result.callEdges()).isEmpty();
        assertThat(result.symbolReferences()).isEmpty();
        assertThat(result.typeHints()).isEmpty();
    }



    @Test
    void extract_noRegistry_returnsEmpty() {
        CodeNode methodNode = node("method:Foo:doWork", NodeKind.METHOD, "doWork");
        String content = "public class Foo { public void doWork() { helper(); } }";
        DetectorContext ctx = new DetectorContext("Foo.java", "java", content, null, null);

        LanguageExtractionResult result = extractor.extract(ctx, methodNode);
        assertThat(result.callEdges()).isEmpty();
    }

    @Test
    void extract_determinism_sameTwice() {
        CodeNode caller = node("method:Foo:doWork", NodeKind.METHOD, "doWork");
        CodeNode callee = node("method:Bar:helper", NodeKind.METHOD, "helper");

        Map<String, CodeNode> registry = Map.of(callee.getId(), callee, callee.getLabel(), callee);
        String content = "public class Foo { public void doWork() { helper(); } }";
        DetectorContext ctx = new DetectorContext("Foo.java", "java", content, registry, null);

        LanguageExtractionResult r1 = extractor.extract(ctx, caller);
        LanguageExtractionResult r2 = extractor.extract(ctx, caller);

        assertThat(r1.callEdges().size()).isEqualTo(r2.callEdges().size());
        if (!r1.callEdges().isEmpty()) {
            assertThat(r1.callEdges().get(0).getId()).isEqualTo(r2.callEdges().get(0).getId());
        }
    }

    @Test
    void extract_confidenceIsPartial_whenCallsFound() {
        // Registry-lookup edges are cross-file by definition → always PARTIAL
        CodeNode caller = node("method:Foo:doWork", NodeKind.METHOD, "doWork");
        CodeNode callee = node("method:Bar:helper", NodeKind.METHOD, "helper");
        Map<String, CodeNode> registry = Map.of(callee.getId(), callee, callee.getLabel(), callee);

        String content = "public class Foo { public void doWork() { helper(); } }";
        DetectorContext ctx = new DetectorContext("Foo.java", "java", content, registry, null);

        LanguageExtractionResult result = extractor.extract(ctx, caller);
        assertThat(result.confidence()).isEqualTo(CapabilityLevel.PARTIAL);
    }

    @Test
    void extract_noFalsePositive_whenTwoClassesHaveSameMethodName() {
        // Two unrelated classes both have process() — match is ambiguous → no CALLS edge
        CodeNode caller = node("method:Alpha:process", NodeKind.METHOD, "process");
        CodeNode calleeA = node("method:Alpha:process", NodeKind.METHOD, "process");
        CodeNode calleeB = node("method:Beta:process", NodeKind.METHOD, "process");

        Map<String, CodeNode> registry = new HashMap<>();
        registry.put(calleeA.getId(), calleeA);
        registry.put(calleeB.getId(), calleeB);

        String content = """
                public class Alpha {
                    public void run() {
                        process();
                    }
                    public void process() {}
                }
                """;

        CodeNode runMethod = node("method:Alpha:run", NodeKind.METHOD, "run");
        DetectorContext ctx = new DetectorContext("Alpha.java", "java", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, runMethod);

        assertThat(result.callEdges())
                .as("ambiguous method name must not produce false-positive CALLS edge")
                .isEmpty();
    }

    @Test
    void extract_confidenceIsPartial_whenNoCallsFound() {
        CodeNode caller = node("method:Foo:doWork", NodeKind.METHOD, "doWork");
        String content = "public class Foo { public void doWork() { } }";
        DetectorContext ctx = new DetectorContext("Foo.java", "java", content, Map.of(), null);

        LanguageExtractionResult result = extractor.extract(ctx, caller);
        assertThat(result.confidence()).isEqualTo(CapabilityLevel.PARTIAL);
    }

    private static CodeNode node(String id, NodeKind kind, String label) {
        CodeNode n = new CodeNode(id, kind, label);
        n.setFqn(id);
        return n;
    }
}
