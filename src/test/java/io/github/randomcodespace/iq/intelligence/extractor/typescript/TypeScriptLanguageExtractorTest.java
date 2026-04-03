package io.github.randomcodespace.iq.intelligence.extractor.typescript;

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

class TypeScriptLanguageExtractorTest {

    private final TypeScriptLanguageExtractor extractor = new TypeScriptLanguageExtractor();

    @Test
    void getLanguage_returnsTypescript() {
        assertThat(extractor.getLanguage()).isEqualTo("typescript");
    }

    @Test
    void extract_namedImport_createsImportEdge() {
        CodeNode source = node("src:index.ts:fn:fetchData", NodeKind.METHOD, "fetchData");
        CodeNode target = node("src:api.ts:class:ApiService", NodeKind.CLASS, "ApiService");

        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);

        String content = """
                import { ApiService } from './api';

                export function fetchData() {
                    return new ApiService().get();
                }
                """;

        DetectorContext ctx = new DetectorContext("index.ts", "typescript", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).hasSize(1);
        CodeEdge edge = result.symbolReferences().get(0);
        assertThat(edge.getKind()).isEqualTo(EdgeKind.IMPORTS);
        assertThat(edge.getTarget().getId()).isEqualTo(target.getId());
    }

    @Test
    void extract_defaultImport_createsImportEdge() {
        CodeNode source = node("src:app.ts:fn:main", NodeKind.METHOD, "main");
        CodeNode target = node("src:config.ts:class:Config", NodeKind.CLASS, "Config");

        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);
        String content = "import Config from './config';";

        DetectorContext ctx = new DetectorContext("app.ts", "typescript", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).hasSize(1);
        assertThat(result.symbolReferences().get(0).getKind()).isEqualTo(EdgeKind.IMPORTS);
    }

    @Test
    void extract_jsDocParams_surfacedAsTypeHints() {
        CodeNode fnNode = node("src:util.ts:fn:process", NodeKind.METHOD, "process");
        fnNode.getProperties().put("lex_comment", "/** @param {string} input - the input @returns {boolean} */");

        DetectorContext ctx = new DetectorContext("util.ts", "typescript", "", Map.of(), null);
        LanguageExtractionResult result = extractor.extract(ctx, fnNode);

        assertThat(result.typeHints()).containsKey("jsdoc_params");
        assertThat(result.typeHints()).containsEntry("jsdoc_return_type", "boolean");
    }

    @Test
    void extract_noImportsNoJsDoc_returnsEmpty() {
        CodeNode node = node("src:empty.ts:fn:noop", NodeKind.METHOD, "noop");
        DetectorContext ctx = new DetectorContext("empty.ts", "typescript", "function noop() {}", Map.of(), null);
        LanguageExtractionResult result = extractor.extract(ctx, node);

        assertThat(result.callEdges()).isEmpty();
        assertThat(result.symbolReferences()).isEmpty();
        assertThat(result.typeHints()).isEmpty();
    }

    @Test
    void extract_confidence_isPartial() {
        CodeNode node = node("src:x.ts:fn:fn", NodeKind.METHOD, "fn");
        DetectorContext ctx = new DetectorContext("x.ts", "typescript", "", Map.of(), null);

        LanguageExtractionResult result = extractor.extract(ctx, node);
        assertThat(result.confidence()).isEqualTo(CapabilityLevel.PARTIAL);
    }

    @Test
    void extract_determinism_sameTwice() {
        CodeNode source = node("src:a.ts:fn:fn", NodeKind.METHOD, "fn");
        CodeNode target = node("src:b.ts:class:MyClass", NodeKind.CLASS, "MyClass");
        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);
        String content = "import { MyClass } from './b';";

        DetectorContext ctx = new DetectorContext("a.ts", "typescript", content, registry, null);
        LanguageExtractionResult r1 = extractor.extract(ctx, source);
        LanguageExtractionResult r2 = extractor.extract(ctx, source);

        assertThat(r1.symbolReferences().size()).isEqualTo(r2.symbolReferences().size());
        if (!r1.symbolReferences().isEmpty()) {
            assertThat(r1.symbolReferences().get(0).getId())
                    .isEqualTo(r2.symbolReferences().get(0).getId());
        }
    }

    @Test
    void extract_sameSymbolMatchedByNamedAndDefaultImport_noDuplicateEdges() {
        CodeNode source = node("src:a.ts:fn:run", NodeKind.METHOD, "run");
        CodeNode target = node("src:b.ts:class:Config", NodeKind.CLASS, "Config");
        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);

        // Both named and default import patterns match Config → same edge id, must deduplicate.
        String content = """
                import { Config } from './b';
                import Config from './b';
                """;

        DetectorContext ctx = new DetectorContext("a.ts", "typescript", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).hasSize(1);
        assertThat(result.symbolReferences().get(0).getKind()).isEqualTo(EdgeKind.IMPORTS);
        assertThat(result.symbolReferences().get(0).getTarget().getId()).isEqualTo(target.getId());
    }

    private static CodeNode node(String id, NodeKind kind, String label) {
        CodeNode n = new CodeNode(id, kind, label);
        n.setFqn(id);
        return n;
    }
}
