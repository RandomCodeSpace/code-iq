package io.github.randomcodespace.iq.intelligence.extractor.python;

import io.github.randomcodespace.iq.detector.DetectorContext;
import io.github.randomcodespace.iq.intelligence.CapabilityLevel;
import io.github.randomcodespace.iq.intelligence.extractor.LanguageExtractionResult;
import io.github.randomcodespace.iq.model.CodeEdge;
import io.github.randomcodespace.iq.model.CodeNode;
import io.github.randomcodespace.iq.model.EdgeKind;
import io.github.randomcodespace.iq.model.NodeKind;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PythonLanguageExtractorTest {

    private final PythonLanguageExtractor extractor = new PythonLanguageExtractor();

    @Test
    void getLanguage_returnsPython() {
        assertThat(extractor.getLanguage()).isEqualTo("python");
    }

    @Test
    void extract_fromImport_createsImportEdge() {
        CodeNode source = node("py:app.py:fn:run", NodeKind.METHOD, "run");
        CodeNode target = node("py:models.py:class:User", NodeKind.CLASS, "User");

        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);

        String content = """
                from models import User

                def run():
                    user = User()
                """;

        DetectorContext ctx = new DetectorContext("app.py", "python", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).hasSize(1);
        CodeEdge edge = result.symbolReferences().get(0);
        assertThat(edge.getKind()).isEqualTo(EdgeKind.IMPORTS);
        assertThat(edge.getTarget().getId()).isEqualTo(target.getId());
    }

    @Test
    void extract_plainImport_createsImportEdge() {
        CodeNode source = node("py:app.py:fn:run", NodeKind.METHOD, "run");
        CodeNode target = node("py:utils.py:module:utils", NodeKind.MODULE, "utils");

        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);
        String content = "import utils\n";

        DetectorContext ctx = new DetectorContext("app.py", "python", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).hasSize(1);
        assertThat(result.symbolReferences().get(0).getKind()).isEqualTo(EdgeKind.IMPORTS);
    }

    @Test
    void extract_typeHints_surfacedFromDefSignature() {
        CodeNode fnNode = node("py:service.py:fn:process", NodeKind.METHOD, "process");

        String content = """
                def process(data: str, count: int) -> bool:
                    return len(data) == count
                """;

        DetectorContext ctx = new DetectorContext("service.py", "python", content, Map.of(), null);
        LanguageExtractionResult result = extractor.extract(ctx, fnNode);

        assertThat(result.typeHints()).containsKey("param_types");
        assertThat(result.typeHints().get("param_types")).contains("data:str").contains("count:int");
        assertThat(result.typeHints()).containsEntry("return_type", "bool");
    }

    @Test
    void extract_selfParamExcluded_fromTypeHints() {
        CodeNode fnNode = node("py:service.py:fn:update", NodeKind.METHOD, "update");

        String content = "def update(self, value: int) -> None:\n    pass\n";

        DetectorContext ctx = new DetectorContext("service.py", "python", content, Map.of(), null);
        LanguageExtractionResult result = extractor.extract(ctx, fnNode);

        assertThat(result.typeHints().getOrDefault("param_types", "")).doesNotContain("self");
    }

    @Test
    void extract_noContent_returnsEmpty() {
        CodeNode fnNode = node("py:empty.py:fn:noop", NodeKind.METHOD, "noop");
        DetectorContext ctx = new DetectorContext("empty.py", "python", null, Map.of(), null);
        LanguageExtractionResult result = extractor.extract(ctx, fnNode);

        assertThat(result.callEdges()).isEmpty();
        assertThat(result.symbolReferences()).isEmpty();
        assertThat(result.typeHints()).isEmpty();
    }

    @Test
    void extract_confidence_isPartial() {
        CodeNode node = node("py:x.py:fn:fn", NodeKind.METHOD, "fn");
        DetectorContext ctx = new DetectorContext("x.py", "python", "", Map.of(), null);

        LanguageExtractionResult result = extractor.extract(ctx, node);
        assertThat(result.confidence()).isEqualTo(CapabilityLevel.PARTIAL);
    }

    @Test
    void extract_determinism_sameTwice() {
        CodeNode fnNode = node("py:svc.py:fn:compute", NodeKind.METHOD, "compute");
        String content = "def compute(x: int, y: float) -> str:\n    pass\n";

        DetectorContext ctx = new DetectorContext("svc.py", "python", content, Map.of(), null);
        LanguageExtractionResult r1 = extractor.extract(ctx, fnNode);
        LanguageExtractionResult r2 = extractor.extract(ctx, fnNode);

        assertThat(r1.typeHints()).isEqualTo(r2.typeHints());
    }

    @Test
    void extract_fromImportAndPlainImportSameSymbol_noDuplicateEdges() {
        CodeNode source = node("py:app.py:fn:run", NodeKind.METHOD, "run");
        CodeNode target = node("py:utils.py:class:Foo", NodeKind.CLASS, "Foo");
        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);

        // Both FROM_IMPORT and PLAIN_IMPORT match Foo → same edge id must deduplicate.
        String content = "from utils import Foo\nimport Foo\n";

        DetectorContext ctx = new DetectorContext("app.py", "python", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).hasSize(1);
        assertThat(result.symbolReferences().get(0).getTarget().getId()).isEqualTo(target.getId());
    }

    @Test
    void extract_aliasedFromImport_edgeCreatedUsingOriginalName() {
        CodeNode source = node("py:app.py:fn:run", NodeKind.METHOD, "run");
        CodeNode target = node("py:models.py:class:User", NodeKind.CLASS, "User");
        Map<String, CodeNode> registry = Map.of(target.getLabel(), target);

        // Alias "as u" must be stripped so lookup is on "User", not "User as u".
        String content = "from models import User as u\n";

        DetectorContext ctx = new DetectorContext("app.py", "python", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        assertThat(result.symbolReferences()).hasSize(1);
        assertThat(result.symbolReferences().get(0).getTarget().getId()).isEqualTo(target.getId());
    }

    @Test
    void extract_ambiguousShortSymbolName_noFalsePositiveEdge() {
        CodeNode source = node("py:app.py:fn:run", NodeKind.METHOD, "run");
        // Two unrelated nodes share the short label "get" — common in Django/dict APIs.
        CodeNode target1 = node("py:dict_utils.py:fn:get", NodeKind.METHOD, "get");
        CodeNode target2 = node("py:list_utils.py:fn:get", NodeKind.METHOD, "get");

        Map<String, CodeNode> registry = new LinkedHashMap<>();
        registry.put(target1.getId(), target1);
        registry.put(target2.getId(), target2);

        String content = "from utils import get\n";

        DetectorContext ctx = new DetectorContext("app.py", "python", content, registry, null);
        LanguageExtractionResult result = extractor.extract(ctx, source);

        // Ambiguous: two nodes labelled "get" → lookupUnambiguous returns null → no edge.
        assertThat(result.symbolReferences()).isEmpty();
    }

    private static CodeNode node(String id, NodeKind kind, String label) {
        CodeNode n = new CodeNode(id, kind, label);
        n.setFqn(id);
        return n;
    }
}
