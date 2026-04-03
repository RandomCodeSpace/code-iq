package io.github.randomcodespace.iq.intelligence.extractor.go;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.extractor.LanguageExtractionResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoLanguageExtractorTest {

    private final GoLanguageExtractor extractor = new GoLanguageExtractor();

    @Test
    void getLanguage_returnsGo() {
        assertThat(extractor.getLanguage()).isEqualTo("go");
    }

    @Test
    void extract_blockImport_createsImportEdge() {
        CodeNode source = node("go:main.go:fn:main", NodeKind.METHOD, "main");
        CodeNode target = node("go:handler.go:module:handler", NodeKind.MODULE, "handler");

        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);

        String content = """
                package main

                import (
                    "myapp/handler"
                    "fmt"
                )

                func main() {
                    handler.Handle()
                }
                """;

        DetectorContext ctx = new DetectorContext("main.go", "go", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).hasSize(1);
        CodeEdge edge = result.symbolReferences().get(0);
        assertThat(edge.getKind()).isEqualTo(EdgeKind.IMPORTS);
        assertThat(edge.getTarget().getId()).isEqualTo(target.getId());
    }

    @Test
    void extract_singleImport_createsImportEdge() {
        CodeNode source = node("go:app.go:fn:run", NodeKind.METHOD, "run");
        CodeNode target = node("go:db.go:module:db", NodeKind.MODULE, "db");

        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);
        String content = "import \"myapp/db\"\n";

        DetectorContext ctx = new DetectorContext("app.go", "go", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).hasSize(1);
        assertThat(result.symbolReferences().get(0).getKind()).isEqualTo(EdgeKind.IMPORTS);
    }

    @Test
    void extract_unknownImport_noEdge() {
        CodeNode source = node("go:app.go:fn:run", NodeKind.METHOD, "run");
        String content = "import \"fmt\"\n";

        DetectorContext ctx = new DetectorContext("app.go", "go", content, Map.of(), null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).isEmpty();
    }

    @Test
    void extract_noContent_returnsEmpty() {
        CodeNode node = node("go:empty.go:fn:noop", NodeKind.METHOD, "noop");
        DetectorContext ctx = new DetectorContext("empty.go", "go", null, Map.of(), null);
        LanguageExtractionResult result = extractor.extract(ctx, node);

        assertThat(result.callEdges()).isEmpty();
        assertThat(result.symbolReferences()).isEmpty();
        assertThat(result.typeHints()).isEmpty();
    }

    @Test
    void extract_confidence_isPartial() {
        CodeNode node = node("go:x.go:fn:fn", NodeKind.METHOD, "fn");
        DetectorContext ctx = new DetectorContext("x.go", "go", "", Map.of(), null);

        LanguageExtractionResult result = extractor.extract(ctx, node);
        assertThat(result.confidence()).isEqualTo(CapabilityLevel.PARTIAL);
    }

    @Test
    void extract_determinism_sameTwice() {
        CodeNode source = node("go:a.go:fn:run", NodeKind.METHOD, "run");
        CodeNode target = node("go:b.go:module:worker", NodeKind.MODULE, "worker");
        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);
        String content = "import (\n    \"app/worker\"\n)\n";

        DetectorContext ctx = new DetectorContext("a.go", "go", content, registry, null);
        LanguageExtractionResult r1 = extractor.extract(ctx, source);
        LanguageExtractionResult r2 = extractor.extract(ctx, source);

        assertThat(r1.symbolReferences().size()).isEqualTo(r2.symbolReferences().size());
        if (!r1.symbolReferences().isEmpty()) {
            assertThat(r1.symbolReferences().get(0).getId())
                    .isEqualTo(r2.symbolReferences().get(0).getId());
        }
    }

    private static CodeNode node(String id, NodeKind kind, String label) {
        CodeNode n = new CodeNode(id, kind, label);
        n.setFqn(id);
        return n;
    }
}
